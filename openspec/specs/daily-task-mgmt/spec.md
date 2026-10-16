# daily-task-mgmt Specification

## Purpose

管理家庭日常事项的最小可验证单元：任务的创建、查询、状态流转（DFA）、部分字段更新与软删除，作为家庭协作系统的核心能力为后续所有模块提供参考范式。
## Requirements
### Requirement: Task resource persistence and retrieval with unified envelope
The system SHALL persist tasks as first-class records with identity, user identifier, title, description, category reference (foreign key to categories.id, nullable to allow uncategorized tasks, ON DELETE SET NULL), due date, priority, source, expiry timestamp, workflow status, creation timestamp and last-updated timestamp; every REST endpoint SHALL return a unified JSON envelope where non-zero code indicates a failure and msg describes it.

#### Scenario: List tasks returns empty envelope on fresh database
- **WHEN** a client calls GET /api/tasks with no tasks in the database
- **THEN** the response status is 200 and the body equals {"code":0,"msg":"success","data":[]}

#### Scenario: Get by id returns 404 envelope for missing task
- **WHEN** a client calls GET /api/tasks/{id} with an id not present in storage
- **THEN** the response status is 404 and the envelope carries a non-zero code plus a "task not found" message

#### Scenario: List includes only non-deleted tasks ordered by createdAt desc
- **WHEN** a client calls GET /api/tasks after creating and then soft-deleting a task
- **THEN** the data array does not contain the deleted task, and remaining tasks are sorted from newest to oldest createdAt

#### Scenario: Create task with valid categoryId persists the reference
- **WHEN** a category with id=7 exists; client POST /api/tasks with {"title":"buy milk","categoryId":7}
- **THEN** the response status is 201 and data.categoryId equals 7

#### Scenario: Create task with categoryId referencing missing category is rejected
- **WHEN** no category with id=999 exists; client POST /api/tasks with {"title":"x","categoryId":999}
- **THEN** the response status is 400 and envelope code is non-zero with a message indicating the referenced category does not exist

#### Scenario: Create task without categoryId persists null reference
- **WHEN** client POST /api/tasks with {"title":"uncategorized task"}
- **THEN** the response status is 201 and data.categoryId is null and the task is retrievable via GET /api/tasks

#### Scenario: Update task categoryId to a valid category changes the reference
- **WHEN** a category with id=3 exists; a task exists with categoryId=null; client PUT /api/tasks/{id} with {"categoryId":3}
- **THEN** the response status is 200 and data.categoryId equals 3

#### Scenario: Update task categoryId to missing category is rejected
- **WHEN** no category with id=999 exists; client PUT /api/tasks/{id} with {"categoryId":999}
- **THEN** the response status is 400 and envelope code is non-zero with a message indicating the referenced category does not exist and the task's categoryId is unchanged

#### Scenario: Update task categoryId to null clears the reference
- **WHEN** a task exists with categoryId=3; client PUT /api/tasks/{id} with {"categoryId":null}
- **THEN** the response status is 200 and data.categoryId is null

### Requirement: Task creation validates input and defaults status
The system SHALL allow creating a task with title and optional description, dueDate, status, priority, source, and categoryId. If status is omitted the record SHALL default to PENDING (renamed from the previous TODO default). Creating with status CANCELLED or COMPLETED SHALL be rejected as a validation error. If priority is omitted it SHALL default to MEDIUM. If source is omitted it SHALL default to MANUAL.

#### Scenario: Create with minimal payload defaults to TODO
(Defaults renamed: TODO -> PENDING as part of 5-value status expansion.)
- **WHEN** a client POST /api/tasks with body {"title":"buy milk"}
- **THEN** the response status is 201 and data.status equals PENDING (was TODO) and data.priority equals MEDIUM and data.source equals MANUAL and data.id is a non-empty identifier and data.title equals "buy milk"

#### Scenario: Create rejects status CANCELLED
- **WHEN** a client POST /api/tasks with {"title":"x","status":"CANCELLED"}
- **THEN** the response status is 400 and the envelope code is non-zero with message explaining CANCELLED is not a valid initial status

#### Scenario: Create rejects status COMPLETED
- **WHEN** a client POST /api/tasks with {"title":"x","status":"COMPLETED"}
- **THEN** the response status is 400 and the envelope code is non-zero with message explaining COMPLETED is not a valid initial status

#### Scenario: Create rejects request without title
- **WHEN** a client POST /api/tasks with {"description":"no title"}
- **THEN** the response status is 400 and envelope indicates title is required

### Requirement: Task update supports partial fields and enforces DFA transitions
The system SHALL accept PUT /api/tasks/{id} with a partial payload (any subset of title, description, dueDate, status, priority, source, categoryId) and merge changes into the existing record. When status is present it MUST comply with the deterministic finite automaton below. Transitions from terminal states (COMPLETED, CANCELLED) to any non-self state SHALL be rejected with 400. The DFA is updated from the 4-state model (TODO/IN_PROGRESS/DONE/CANCELLED) to the new 5-state model (DRAFT/PENDING/IN_PROGRESS/COMPLETED/CANCELLED); previously allowed DONE->TODO reversal was never allowed (was DONE->CANCELLED only).

Legal DFA transitions (source -> allowed targets):
- DRAFT -> DRAFT, PENDING, CANCELLED
- PENDING -> PENDING, IN_PROGRESS, COMPLETED, CANCELLED
- IN_PROGRESS -> PENDING, IN_PROGRESS, COMPLETED, CANCELLED
- COMPLETED -> COMPLETED (self only)
- CANCELLED -> CANCELLED (self only)

When a transition from DRAFT to PENDING occurs, the system SHALL clear expiresAt to null.

#### Scenario: Mark TODO as DONE and then forbid reversing to TODO
(Mapped to the updated 5-state model: TODO renamed PENDING, DONE renamed COMPLETED; COMPLETED remains terminal so reversal to PENDING is still forbidden.)
- **WHEN** a task is PENDING (was TODO); client PUT status COMPLETED (was DONE); then PUT status PENDING (was TODO)
- **THEN** the first PUT returns 200 with data.status=COMPLETED; the second PUT returns 400 with non-zero code and a DFA-violation message

#### Scenario: CANCELLED cannot move to any other state including back to TODO
(Mapped to updated 5-state model: CANCELLED unchanged; TODO now PENDING. Among DRAFT / PENDING / IN_PROGRESS / COMPLETED / CANCELLED, CANCELLED only allows self.)
- **WHEN** a task is CANCELLED; client PUT with any status among DRAFT / PENDING / IN_PROGRESS / COMPLETED / CANCELLED
- **THEN** PUTs for DRAFT / PENDING / IN_PROGRESS / COMPLETED each return 400 with non-zero code; record remains CANCELLED; self-transition CANCELLED->CANCELLED returns 200

#### Scenario: PENDING can transition directly to COMPLETED
- **WHEN** a task is PENDING; client PUT status COMPLETED
- **THEN** the response status is 200 and data.status equals COMPLETED

#### Scenario: Mark PENDING as IN_PROGRESS then COMPLETED, then forbid reversing to PENDING
- **WHEN** a task is PENDING; client PUT status IN_PROGRESS; then PUT status COMPLETED; then PUT status PENDING
- **THEN** the first two PUTs return 200 with matching status; the third PUT returns 400 with non-zero code and a DFA-violation message

#### Scenario: DRAFT can be confirmed to PENDING or cancelled
- **WHEN** a task is DRAFT; client PUT status PENDING; then a different DRAFT task; client PUT status CANCELLED
- **THEN** the first PUT returns 200 with data.status=PENDING and data.expiresAt is null; the second PUT returns 200 with data.status=CANCELLED

#### Scenario: Partial update edits title only leaving other fields untouched
- **WHEN** a task exists with title="A", description="B", dueDate="2026-09-01", priority="HIGH"; client PUT {"title":"C"}
- **THEN** returned data.title is "C" and data.description is still "B" and data.dueDate is still "2026-09-01" and data.priority is still "HIGH"

### Requirement: Soft delete semantics
Deleting a task via `DELETE /api/tasks/{id}` SHALL perform a soft delete such that the row remains in storage but is excluded from `GET /api/tasks` and `GET /api/tasks/{id}`. A second delete against the same identifier SHALL respond with 404 identical to a never-existed id.

#### Scenario: Soft delete hides the record from subsequent reads
- **WHEN** a task exists at id=42; client `DELETE /api/tasks/42`; then `GET /api/tasks/42`; then `GET /api/tasks`
- **THEN** the DELETE returns 204 or 200; the GET-by-id returns 404; the list `data` does not include id=42

#### Scenario: Double delete yields 404 after first success
- **WHEN** a task exists; client DELETE succeeds (2xx); then the same DELETE is repeated
- **THEN** the second DELETE returns 404 with envelope non-zero code / "task not found" message

### Requirement: Workflow states exposed as TaskStatus enum
The system SHALL represent a task workflow state as the enumeration TaskStatus with exactly five values: DRAFT, PENDING, IN_PROGRESS, COMPLETED, CANCELLED (expanded from the previous four TODO, IN_PROGRESS, DONE, CANCELLED). Legacy storage values TODO and DONE are migrated in the database to PENDING and COMPLETED respectively by Flyway V2, so the API only returns the new five names. Any unknown status value on the wire SHALL be rejected as 400 validation error.

#### Scenario: Unknown status string on create returns 400
- **WHEN** a client POST /api/tasks {"title":"x","status":"SOMETHING"}
- **THEN** the response status is 400 with validation error on field status

#### Scenario: List returns all four legal status strings as they were stored
(Status values migrated: TODO->PENDING, DONE->COMPLETED; plus two new values DRAFT, CANCELLED. Result set contains the five post-migration strings below.)
- **WHEN** four legacy tasks existed in statuses TODO / IN_PROGRESS / DONE / CANCELLED and have been migrated; additional DRAFT task exists
- **THEN** GET /api/tasks returns records whose status field values are among DRAFT, PENDING, IN_PROGRESS, COMPLETED, CANCELLED (TODO and DONE no longer appear on the wire)

#### Scenario: List returns all five legal status strings as they were stored
- **WHEN** five tasks exist in statuses DRAFT / PENDING / IN_PROGRESS / COMPLETED / CANCELLED
- **THEN** GET /api/tasks returns five records whose status field matches those exact casing and spelling

### Requirement: Frontend TaskView performs full CRUD loop over REST API
The frontend SPA SHALL provide a TaskView route that renders tasks retrieved from GET /api/tasks, offers a form to POST /api/tasks, and exposes a per-row action that DELETE /api/tasks/{id} and then refreshes the list. All HTTP calls SHALL target the backend via /api/... (proxied in development by the frontend dev server).

#### Scenario: TaskView initial load shows empty state when backend returns empty list
- **WHEN** a user navigates to /tasks route and the backend responds to GET /api/tasks with data: []
- **THEN** the view renders an empty-state message and the create form remains visible

#### Scenario: Submitting the create form appends one item to the list without full-page reload
- **WHEN** the user fills the form title="dishes" and clicks Submit; the backend POST /api/tasks returns 201 with data.id and data.status=PENDING (was TODO, status renamed)
- **THEN** the GET /api/tasks call triggered after success includes the new "dishes" item in the list without reloading the page

#### Scenario: Click delete on a row removes it and backend confirms soft delete via 404 on refetch
- **WHEN** the user clicks delete on row "dishes"; backend DELETE returns 2xx; next list fetch completes
- **THEN** row "dishes" is no longer present in the DOM and reloading the page keeps it absent

### Requirement: Task list supports multi-dimensional filtering
The system SHALL support optional query parameters on GET /api/tasks for filtering by status, categoryId, source, keyword (title and description LIKE match), and dueBefore (tasks with dueDate on or before the given date). When no filter parameters are provided, all non-deleted tasks SHALL be returned. When multiple filters are provided, they SHALL be combined with AND logic.

#### Scenario: Filter by status returns only matching tasks
- **WHEN** a client calls GET /api/tasks?status=PENDING and three tasks exist with statuses PENDING, IN_PROGRESS, COMPLETED
- **THEN** the response data array contains exactly one record whose status is PENDING

#### Scenario: Filter by keyword matches title or description case-insensitively
- **WHEN** a client calls GET /api/tasks?keyword=milk and tasks exist with titles "buy milk" and "clean floor" and description containing "milk and eggs"
- **THEN** the response data array contains the "buy milk" task and the task whose description mentions milk, but not the "clean floor" task

#### Scenario: Multiple filters combine with AND logic
- **WHEN** a client calls GET /api/tasks?status=PENDING&source=MANUAL and tasks exist with (PENDING, MANUAL), (PENDING, AGENT), (IN_PROGRESS, MANUAL)
- **THEN** the response data array contains exactly one record whose status is PENDING and source is MANUAL

#### Scenario: Filter by dueBefore returns tasks with dueDate on or before the date
- **WHEN** a client calls GET /api/tasks?dueBefore=2026-09-01 and tasks exist with dueDate 2026-08-31, 2026-09-01, 2026-09-02, and null
- **THEN** the response data array contains the 2026-08-31 and 2026-09-01 tasks but not the 2026-09-02 task; tasks with null dueDate are excluded when dueBefore filter is present

### Requirement: Draft proposal lifecycle with expiry
The system SHALL support a DRAFT status for tasks representing unconfirmed proposals. When a task is created with status DRAFT, the system SHALL set expiresAt to 24 hours after creation. DRAFT tasks past their expiresAt SHALL be automatically transitioned to CANCELLED by a scheduled background task. A DRAFT task SHALL transition to PENDING when the client confirms it via PUT status=PENDING, at which point expiresAt SHALL be cleared to null.

#### Scenario: Create draft task with auto expiry
- **WHEN** a client POST /api/tasks with {"title":"proposal","status":"DRAFT"}
- **THEN** the response status is 201 and data.status equals DRAFT and data.expiresAt is a non-null timestamp approximately 24 hours in the future

#### Scenario: Confirm draft to pending clears expiresAt
- **WHEN** a DRAFT task exists with expiresAt set; client PUT /api/tasks/{id} with status=PENDING
- **THEN** the response status is 200 and data.status equals PENDING and data.expiresAt is null

#### Scenario: Expired draft is cancelled by scheduled task
- **WHEN** a DRAFT task exists with expiresAt in the past; the scheduled expiry task runs
- **THEN** the task status is updated to CANCELLED and subsequent GET /api/tasks does not include it in default results

### Requirement: tasks 表由 user_id 单用户归属改造为 family_id + created_by + assignee_id 三字段归属体系
The system SHALL, in Flyway V4 migration, replace the single tasks.user_id column with three columns: family_id BIGINT NOT NULL REFERENCES families(id) ON DELETE RESTRICT, created_by BIGINT REFERENCES users(id) ON DELETE SET NULL, and assignee_id BIGINT REFERENCES users(id) ON DELETE SET NULL. V4 line 1-2 SHALL run DELETE FROM tasks; DELETE FROM categories; to clear legacy user-scoped data before column alteration. Indexes SHALL be created on tasks(family_id), tasks(created_by), and tasks(assignee_id).
#### Scenario: V4 migration replaces user_id with family_id + created_by + assignee_id on tasks table
- **WHEN** Flyway migrates from version V3 to V4 on a PostgreSQL 16 database with existing tasks rows
- **THEN** psql shows columns family_id NOT NULL, created_by, assignee_id and column user_id is absent; shows indexes idx_tasks_family, idx_tasks_created_by, idx_tasks_assignee
#### Scenario: Tasks V4 clears legacy user-scoped rows before schema change
- **WHEN** Flyway executes V4 lines 1-2
- **THEN** SELECT COUNT(*) FROM tasks immediately returns 0 before any ALTER TABLE statement executes

### Requirement: 任务 CRUD 与过滤查询强制按 X-Family-Id Header 限定家庭作用域
The system SHALL require the X-Family-Id HTTP header on all /api/tasks/** endpoints (excluding /api/auth/**). The AuthInterceptor SHALL validate that the current user (from X-Access-Token) is an active member (family_members.left_at IS NULL) of the specified family; if not, return 403 FAMILY_NOT_MEMBER. All TaskRepository query methods SHALL accept a familyId parameter and add WHERE family_id = :familyId predicate.
#### Scenario: Task list endpoint filters results by X-Family-Id only
- **GIVEN** Family A has 3 tasks and Family B has 2 tasks owned by the same logged-in user who is active in both families
- **WHEN** GET /api/tasks is called with X-Family-Id: A
- **THEN** the response data array contains exactly 3 tasks, all with familyId = A; no task from Family B leaks into the result
#### Scenario: Task endpoint without X-Family-Id header rejects request
- **WHEN** GET /api/tasks is called with a valid X-Access-Token but missing X-Family-Id
- **THEN** the response is 4xx with code FAMILY_HEADER_REQUIRED and Chinese message 请在请求头中指定家庭ID

### Requirement: 任务读写权限按 OWNER / ADULT / CHILD 角色矩阵强制执行
The system SHALL enforce role-based access control on task operations:
- OWNER and ADULT roles: full CRUD + any status transition allowed by DFA
- CHILD role: CREATE task allowed; when both assigneeId and assigneeProfileId are omitted, assignee defaults to the current CHILD user and its active profile; explicitly assigning another user/profile is denied. UPDATE task is allowed only if self is created_by OR self is assignee_id OR assignee_profile_id belongs to self, and a CHILD cannot reassign the task to another member. DELETE task is always denied (403 ROLE_NO_PERMISSION); category CUD is always denied; READ is allowed.

#### Scenario: CHILD role creates task with assignee defaulting to self
- **GIVEN** User X has CHILD role in family F, X-Access-Token valid, X-Family-Id = F
- **WHEN** POST /api/tasks request body omits assigneeId and assigneeProfileId
- **THEN** the created task has assigneeId = X.id, assigneeProfileId = X's active profile id, and createdBy = X.id; status 201

#### Scenario: CHILD role cannot create task assigned to another member
- **GIVEN** User X has CHILD role in family F and User Y is another active member
- **WHEN** X creates a task with assigneeId or assigneeProfileId belonging to Y
- **THEN** response is 403 ROLE_NO_PERMISSION and no task is created

#### Scenario: CHILD role updates self-created or self-assigned task
- **GIVEN** User X has CHILD role in family F and task T was created by X or assigned to X
- **WHEN** X updates T without reassigning it to another member
- **THEN** the update succeeds if it also satisfies the task validation and DFA rules

#### Scenario: CHILD role cannot update unrelated task
- **GIVEN** User X has CHILD role in family F and task T was neither created by nor assigned to X
- **WHEN** X attempts to update T
- **THEN** response is 403 ROLE_NO_PERMISSION and T remains unchanged

#### Scenario: CHILD role attempting to DELETE any task gets 403
- **GIVEN** User X has CHILD role in family F; task T was created by X itself
- **WHEN** DELETE /api/tasks/{T.id} is called as user X
- **THEN** response is 403 with code ROLE_NO_PERMISSION and Chinese message 孩子角色不能删除任务，请让家长帮忙操作

#### Scenario: ADULT role can DELETE any task in the family
- **GIVEN** User Y has ADULT role in family F; task T was created by another member Z
- **WHEN** DELETE /api/tasks/{T.id} is called as user Y
- **THEN** task T deletedAt is set to NOW; response is 200 OK

### Requirement: 任务响应 DTO 追加 createdBy / assigneeId / familyId 字段并支持中文昵称解析
The system SHALL extend TaskResponse to include familyId, createdBy (Long), assigneeId (Long or null), createdByName (String?), and assigneeName (String?). Nickname resolution SHALL use LEFT JOIN users table.
#### Scenario: TaskResponse includes creator and assignee nicknames after V4
- **GIVEN** Task T in family F has created_by=User1 (nickname 爸爸) and assignee_id=User2 (nickname 小明)
- **WHEN** GET /api/tasks/{T.id} is called with X-Family-Id=F
- **THEN** response JSON includes familyId=F.id, createdBy=User1.id, assigneeId=User2.id, createdByName=爸爸, assigneeName=小明

### Requirement: 任务 DFA 状态流转维持原矩阵不变，新增 created_by/assignee 权限检查
The system SHALL preserve the original TaskStatus DFA matrix: DRAFT->{DRAFT,PENDING,CANCELLED}; PENDING->{PENDING,IN_PROGRESS,COMPLETED,CANCELLED}; IN_PROGRESS->{PENDING,IN_PROGRESS,COMPLETED,CANCELLED}; COMPLETED->{COMPLETED}; CANCELLED->{CANCELLED}. CHILD role attempting a transition on non-self-created/non-self-assigned task gets 403. DRAFT auto-expiry job SHALL add family_id predicate.
#### Scenario: CHILD transitions self-assigned task from PENDING to IN_PROGRESS succeeds
- **GIVEN** User C (CHILD role) is assignee of task T (status PENDING) in family F
- **WHEN** C calls PUT /api/tasks/{T.id}/status with body status=IN_PROGRESS
- **THEN** task T.status becomes IN_PROGRESS; response 200
#### Scenario: CHILD transitions task created and assigned to others gets 403
- **GIVEN** User C (CHILD role) in family F; task T was created by ADULT A and assigned to A; status=PENDING
- **WHEN** C calls PUT /api/tasks/{T.id}/status status=IN_PROGRESS
- **THEN** response 403 with Chinese message 这个任务不是您创建或分配给您的，无法修改状态
#### Scenario: DRAFT expiry scheduled job adds family_id predicate
- **GIVEN** Family F1 has 5 DRAFT tasks older than 24h and Family F2 has 3; the scheduled job runs
- **WHEN** the job UPDATE executes
- **THEN** exactly 8 tasks transition to CANCELLED; query includes WHERE family_id IS NOT NULL predicate

