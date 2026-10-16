## Purpose

管理家庭日常事项的最小可验证单元：任务的创建、查询、状态流转（DFA）、部分字段更新与软删除，作为家庭协作系统的核心能力为后续所有模块提供参考范式。

## ADDED Requirements

### Requirement: Task resource persistence and retrieval with unified envelope
The system SHALL persist tasks as first-class records with identity, title, description, due date, workflow status, creation timestamp and last-updated timestamp; every REST endpoint SHALL return a unified JSON envelope `{code, msg, data}` where non-zero `code` indicates a failure and `msg` describes it.

#### Scenario: List tasks returns empty envelope on fresh database
- **WHEN** a client calls `GET /api/tasks` with no tasks in the database
- **THEN** the response status is 200 and the body equals `{"code":0,"msg":"success","data":[]}`

#### Scenario: Get by id returns 404 envelope for missing task
- **WHEN** a client calls `GET /api/tasks/{id}` with an id not present in storage
- **THEN** the response status is 404 and the envelope carries a non-zero code plus a "task not found" message

#### Scenario: List includes only non-deleted tasks ordered by createdAt desc
- **WHEN** a client calls `GET /api/tasks` after creating and then soft-deleting a task
- **THEN** the `data` array does not contain the deleted task, and remaining tasks are sorted from newest to oldest `createdAt`

### Requirement: Task creation validates input and defaults status
The system SHALL allow creating a task with `title` and optional `description`, `dueDate`, and `status`. If `status` is omitted the record SHALL default to `TODO`. Creating with status `CANCELLED` SHALL be rejected as a validation error.

#### Scenario: Create with minimal payload defaults to TODO
- **WHEN** a client `POST /api/tasks` with body `{"title":"buy milk"}`
- **THEN** the response status is 201 and `data.status` equals `TODO` and `data.id` is a non-empty identifier and `data.title` equals `"buy milk"`

#### Scenario: Create rejects status CANCELLED
- **WHEN** a client `POST /api/tasks` with `{"title":"x","status":"CANCELLED"}`
- **THEN** the response status is 400 and the envelope `code` is non-zero with message explaining CANCELLED is not a valid initial status

#### Scenario: Create rejects request without title
- **WHEN** a client `POST /api/tasks` with `{"description":"no title"}`
- **THEN** the response status is 400 and envelope indicates title is required

### Requirement: Task update supports partial fields and enforces DFA transitions
The system SHALL accept `PUT /api/tasks/{id}` with a partial payload (any subset of `title`, `description`, `dueDate`, `status`) and merge changes into the existing record. When `status` is present it MUST comply with the deterministic finite automaton below. Transitions `DONE -> TODO` and `CANCELLED -> *` (any target) SHALL be rejected with 400.

Legal DFA transitions (source -> allowed targets):
- TODO -> IN_PROGRESS, DONE, CANCELLED
- IN_PROGRESS -> TODO, DONE, CANCELLED
- DONE -> CANCELLED (only)
- CANCELLED -> (none)

#### Scenario: Mark TODO as DONE and then forbid reversing to TODO
- **WHEN** a task is `TODO`; client `PUT` status `DONE`; then immediately `PUT` status `TODO`
- **THEN** the first PUT returns 200 with `data.status=DONE`; the second PUT returns 400 with non-zero code and a DFA-violation message

#### Scenario: CANCELLED cannot move to any other state including back to TODO
- **WHEN** a task is `CANCELLED`; client `PUT` with any status among TODO / IN_PROGRESS / DONE / CANCELLED
- **THEN** each PUT returns 400 with non-zero code; record remains CANCELLED

#### Scenario: Partial update edits title only leaving other fields untouched
- **WHEN** a task exists with title="A", description="B", dueDate="2026-09-01"; client `PUT {"title":"C"}`
- **THEN** returned `data.title` is "C" and `data.description` is still "B" and `data.dueDate` is still "2026-09-01"

### Requirement: Soft delete semantics
Deleting a task via `DELETE /api/tasks/{id}` SHALL perform a soft delete such that the row remains in storage but is excluded from `GET /api/tasks` and `GET /api/tasks/{id}`. A second delete against the same identifier SHALL respond with 404 identical to a never-existed id.

#### Scenario: Soft delete hides the record from subsequent reads
- **WHEN** a task exists at id=42; client `DELETE /api/tasks/42`; then `GET /api/tasks/42`; then `GET /api/tasks`
- **THEN** the DELETE returns 204 or 200; the GET-by-id returns 404; the list `data` does not include id=42

#### Scenario: Double delete yields 404 after first success
- **WHEN** a task exists; client DELETE succeeds (2xx); then the same DELETE is repeated
- **THEN** the second DELETE returns 404 with envelope non-zero code / "task not found" message

### Requirement: Workflow states exposed as TaskStatus enum
The system SHALL represent a task workflow state as the enumeration `TaskStatus` with exactly four values: `TODO`, `IN_PROGRESS`, `DONE`, `CANCELLED`. Any unknown status value on the wire SHALL be rejected as 400 validation error.

#### Scenario: Unknown status string on create returns 400
- **WHEN** a client `POST /api/tasks {"title":"x","status":"SOMETHING"}`
- **THEN** the response status is 400 with validation error on field `status`

#### Scenario: List returns all four legal status strings as they were stored
- **WHEN** four tasks exist in statuses TODO / IN_PROGRESS / DONE / CANCELLED
- **THEN** `GET /api/tasks` returns four records whose `status` field matches those exact casing and spelling

### Requirement: Frontend TaskView performs full CRUD loop over REST API
The frontend SPA SHALL provide a `TaskView` route that renders tasks retrieved from `GET /api/tasks`, offers a form to `POST /api/tasks`, and exposes a per-row action that `DELETE /api/tasks/{id}` and then refreshes the list. All HTTP calls SHALL target the backend via `/api/...` (proxied in development by the frontend dev server).

#### Scenario: TaskView initial load shows empty state when backend returns empty list
- **WHEN** a user navigates to `/tasks` route and the backend responds to `GET /api/tasks` with `data: []`
- **THEN** the view renders an empty-state message and the create form remains visible

#### Scenario: Submitting the create form appends one item to the list without full-page reload
- **WHEN** the user fills the form `title="dishes"` and clicks Submit; the backend `POST /api/tasks` returns 201 with `data.id` and `data.status=TODO`
- **THEN** the `GET /api/tasks` call triggered after success includes the new "dishes" item in the list without reloading the page

#### Scenario: Click delete on a row removes it and backend confirms soft delete via 404 on refetch
- **WHEN** the user clicks delete on row "dishes"; backend DELETE returns 2xx; next list fetch completes
- **THEN** row "dishes" is no longer present in the DOM and reloading the page keeps it absent
