# Spec: 日常任务管理（增量变更）

**Capability id**: daily-task-mgmt（对 add-family-member 变更而言为 MODIFIED 能力）

**Change**: add-family-member

---

## ADDED Requirements

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
- CHILD role: CREATE task allowed (assignee defaults to self); UPDATE task allowed only if self is created_by OR self is assignee_id; DELETE task always denied (403 ROLE_NO_PERMISSION); category CUD always denied; READ allowed
#### Scenario: CHILD role creates task with assignee defaulting to self
- **GIVEN** User X has CHILD role in family F, X-Access-Token valid, X-Family-Id = F
- **WHEN** POST /api/tasks request body omits assigneeId
- **THEN** the created task has assigneeId = X.id and createdBy = X.id; status 201
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

