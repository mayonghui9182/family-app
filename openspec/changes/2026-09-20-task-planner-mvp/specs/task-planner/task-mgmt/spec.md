# task-planner/task-mgmt Delta Specification

## Purpose

工作任务计划子系统的数据管理模块：任务、子待办、注意事项、分类四类资源的持久化与 REST CRUD，固定 5 值状态机（draft/pending/in_progress/completed/cancelled）的合法流转，AI 提案 draft 的 24 小时过期与用户确认生命周期，物理删除与级联策略，以及通过 `/mcp`（Streamable HTTP）向 agent-service 暴露的任务数据访问工具契约。

## ADDED Requirements

### Requirement: Task persistence and filtered retrieval

The system SHALL persist tasks with identity, user identifier (fixed MVP value, reserved for multi-user), title, description, category reference, status (one of `draft`, `pending`, `in_progress`, `completed`, `cancelled`), priority (one of `high`, `medium`, `low`), due date, parent task reference, expiry timestamp, source (one of `manual`, `agent`, `routine`, `schedule`, `trigger`), creation timestamp and last-updated timestamp. The system SHALL expose `GET /api/tasks` returning a unified JSON envelope whose `data` is an array of tasks, supporting the combinable query filters `status`, `category`, `source`, `keyword` (matched against title and description) and `due_before`. Results SHALL be ordered by creation timestamp descending. Draft tasks whose `expires_at` is in the past SHALL be excluded from every list response.

#### Scenario: Empty database returns an empty envelope

- **WHEN** a client calls `GET /api/tasks` and no task exists
- **THEN** the response status is 200 and the body equals `{"code":0,"msg":"success","data":[]}`

#### Scenario: Filter by status returns only matching tasks

- **WHEN** tasks exist with status `pending`, `in_progress` and `completed`, and a client calls `GET /api/tasks?status=pending`
- **THEN** `data` contains only the tasks whose status is `pending`

#### Scenario: Filters combine in a single request

- **WHEN** a client calls `GET /api/tasks?status=pending&category=<uuid>&due_before=2026-09-30T00:00:00Z`
- **THEN** `data` contains only tasks satisfying all three conditions

#### Scenario: Keyword filter matches title or description

- **WHEN** a task titled "准备产品发布会" and a task whose description contains "发布会" exist, and a client calls `GET /api/tasks?keyword=发布会`
- **THEN** `data` contains both tasks

#### Scenario: Expired draft is hidden from the list

- **WHEN** a draft task exists whose `expires_at` is earlier than the current time and a client calls `GET /api/tasks`
- **THEN** `data` does not contain that draft task

### Requirement: Task detail aggregates subtasks and notes

The system SHALL expose `GET /api/tasks/{id}` returning the task fields together with all its subtasks ordered by `sort_order` ascending then `id` ascending, the notes whose target is the task, and for each subtask the notes whose target is that subtask. Requesting an unknown identifier SHALL return 404 with a non-zero envelope code.

#### Scenario: Detail contains ordered subtasks and their notes

- **WHEN** a task has subtasks with `sort_order` 2 and 1 and the subtask with `sort_order` 1 carries one note, and a client calls `GET /api/tasks/{id}`
- **THEN** `data.subtasks` lists the `sort_order` 1 subtask first and that subtask's `notes` array has length 1

#### Scenario: Unknown task identifier returns 404

- **WHEN** a client calls `GET /api/tasks/{id}` with an identifier that does not exist
- **THEN** the response status is 404 and the envelope code is non-zero

### Requirement: Task creation validates input and applies defaults

The system SHALL allow creating a task via `POST /api/tasks` with `title` (required, non-empty) and optional `description`, `category_id`, `priority`, `due_date`, `status`. When omitted, `status` SHALL default to `pending`, `priority` SHALL default to `medium`, `source` SHALL be `manual`, `user_id` SHALL be the fixed MVP value and `expires_at` SHALL be null. A `category_id` that does not reference an existing category SHALL be rejected. The response SHALL be 201 with the created record in `data`.

#### Scenario: Minimal payload applies defaults

- **WHEN** a client calls `POST /api/tasks` with body `{"title":"写季度总结"}`
- **THEN** the response status is 201 and `data.status` equals `"pending"` and `data.priority` equals `"medium"` and `data.source` equals `"manual"` and `data.expires_at` is null

#### Scenario: Missing title is rejected

- **WHEN** a client calls `POST /api/tasks` with body `{"description":"没有标题"}`
- **THEN** the response status is 400 and the envelope indicates that title is required

#### Scenario: Unknown category reference is rejected

- **WHEN** a client calls `POST /api/tasks` with a `category_id` that does not exist
- **THEN** the response status is 400 and the envelope code is non-zero

### Requirement: Task update enforces the five-state status machine

The system SHALL allow partial updates of `title`, `description`, `status`, `priority`, `due_date` and `category_id` via `PUT /api/tasks/{id}`. Status changes SHALL only be accepted along the transitions `draft→pending`, `draft→cancelled`, `pending→in_progress`, `pending→completed`, `pending→cancelled`, `in_progress→completed`, `in_progress→cancelled`. Any other status change, including any change away from `completed` or `cancelled`, SHALL be rejected with 400 and a non-zero envelope code. `updated_at` SHALL be refreshed on every successful update.

#### Scenario: Confirming a draft moves it to pending

- **WHEN** a task with status `draft` is updated via `PUT /api/tasks/{id}` with body `{"status":"pending"}`
- **THEN** the response status is 200 and `data.status` equals `"pending"`

#### Scenario: Normal progression is accepted

- **WHEN** a `pending` task is updated to `in_progress` and later to `completed`
- **THEN** both updates return 200 and the final `data.status` equals `"completed"`

#### Scenario: Skipping states is rejected

- **WHEN** a task with status `draft` is updated with body `{"status":"completed"}`
- **THEN** the response status is 400 and the envelope code is non-zero

#### Scenario: Terminal states cannot be reopened

- **WHEN** a task with status `completed` is updated with body `{"status":"pending"}`
- **THEN** the response status is 400 and the envelope code is non-zero

#### Scenario: Successful update refreshes updated_at

- **WHEN** a task is updated with body `{"priority":"high"}`
- **THEN** the response status is 200 and `data.updated_at` is later than the value returned before the update

### Requirement: Draft proposals expire 24 hours after creation

A task created with `status=draft` SHALL carry `expires_at` equal to its creation time plus 24 hours; tasks in any other status SHALL have `expires_at` null. Confirming a draft (`draft→pending`) SHALL clear `expires_at`. List and detail queries SHALL filter drafts with the predicate `status != 'draft' OR expires_at > now()`. Expired drafts SHALL be turned into `cancelled` by the daily summary run so that the record is retained for learning (see `task-planner/template-learning`).

#### Scenario: Draft created through MCP carries a 24-hour expiry

- **WHEN** a draft task is created at time T
- **THEN** its `expires_at` equals T plus 24 hours (tolerance 1 minute)

#### Scenario: Confirmation clears the expiry

- **WHEN** a draft task is updated to status `pending`
- **THEN** the stored `expires_at` is null

#### Scenario: Non-draft tasks never carry an expiry

- **WHEN** a client creates a task via `POST /api/tasks` without specifying status
- **THEN** `data.expires_at` is null

### Requirement: Task deletion is physical and cascades

`DELETE /api/tasks/{id}` SHALL physically remove the task together with all its subtasks and all notes targeting the task or any of its subtasks. Tasks in status `cancelled` SHALL NOT be removed automatically and SHALL remain retrievable, because they are learning material. Deleting an unknown identifier SHALL return 404.

#### Scenario: Deleting a task removes its subtasks and notes

- **WHEN** a task with two subtasks, one task-level note and one subtask-level note is deleted
- **THEN** the response status is 200 and a subsequent `GET /api/tasks/{id}` returns 404 and no subtask or note of that task is retrievable

#### Scenario: Cancelled tasks are retained

- **WHEN** a task is updated to status `cancelled` and the daily summary runs
- **THEN** `GET /api/tasks?status=cancelled` still returns that task

#### Scenario: Deleting an unknown task returns 404

- **WHEN** a client calls `DELETE /api/tasks/{id}` with an identifier that does not exist
- **THEN** the response status is 404

### Requirement: Subtask management marks AI-generated origin

The system SHALL allow creating a subtask via `POST /api/tasks/{id}/subtasks` with `title` (required, non-empty), optional `description`, `sort_order` (default 0) and `status` (default `pending`). Subtasks created through REST SHALL have `is_ai_generated=false`; the flag SHALL only be true for subtasks created by the agent through MCP. `PUT /api/subtasks/{id}` SHALL update `title`, `description`, `sort_order` and `status` under the same five-value status machine as tasks. `DELETE /api/subtasks/{id}` SHALL physically delete the subtask and its notes. Creating a subtask under an unknown task SHALL return 404.

#### Scenario: Manually added subtask is not marked as AI-generated

- **WHEN** a client calls `POST /api/tasks/{id}/subtasks` with body `{"title":"打印材料"}`
- **THEN** the response status is 201 and `data.is_ai_generated` is false and `data.sort_order` is 0 and `data.status` equals `"pending"`

#### Scenario: Subtask status transition is validated

- **WHEN** a `pending` subtask is updated with body `{"status":"completed"}`
- **THEN** the response status is 200, and a further update with body `{"status":"draft"}` returns 400

#### Scenario: Deleting a subtask removes its notes

- **WHEN** a subtask carrying one note is deleted
- **THEN** the response status is 200 and the note is no longer retrievable

#### Scenario: Unknown parent task returns 404

- **WHEN** a client calls `POST /api/tasks/{id}/subtasks` with an unknown task identifier
- **THEN** the response status is 404

### Requirement: Notes attach to a task or to a subtask

The system SHALL allow creating a note via `POST /api/tasks/{id}/notes` (target_type `task`) and `POST /api/subtasks/{id}/notes` (target_type `subtask`) with required non-empty `content`. `DELETE /api/notes/{id}` SHALL physically delete the note. An empty or missing content SHALL be rejected with 400, and an unknown target SHALL return 404.

#### Scenario: Task-level note is created with target_type task

- **WHEN** a client calls `POST /api/tasks/{id}/notes` with body `{"content":"提前2周开始准备"}`
- **THEN** the response status is 201 and `data.target_type` equals `"task"` and `data.target_id` equals the task identifier

#### Scenario: Subtask-level note is created with target_type subtask

- **WHEN** a client calls `POST /api/subtasks/{id}/notes` with body `{"content":"确认场地音响"}`
- **THEN** the response status is 201 and `data.target_type` equals `"subtask"`

#### Scenario: Empty content is rejected

- **WHEN** a client calls `POST /api/tasks/{id}/notes` with body `{"content":""}`
- **THEN** the response status is 400

### Requirement: Category management with unique names and merge

The system SHALL expose `GET /api/categories` returning all categories ordered by creation timestamp ascending, `POST /api/categories` creating a category with a required non-empty `name` that is unique across categories and an optional `description`, and `PUT /api/categories/{id}` renaming a category under the same uniqueness rule. `POST /api/categories/{id}/merge` with body `{"target_category_id":"<uuid>"}` SHALL reassign every task and every rule template of the source category to the target category and then remove the source category. Merging a category into itself, into an unknown target, or creating/renaming to a duplicate name SHALL be rejected with 400.

#### Scenario: Creating a duplicate category name is rejected

- **WHEN** a category named "市场推广" exists and a client calls `POST /api/categories` with body `{"name":"市场推广"}`
- **THEN** the response status is 400 and the envelope code is non-zero

#### Scenario: Merge reassigns tasks and removes the source category

- **WHEN** category A holds two tasks and one decomposition template, and a client calls `POST /api/categories/{A}/merge` with `{"target_category_id":"<B>"}`
- **THEN** the response status is 200, both tasks and the template now reference B, and `GET /api/categories` no longer contains A

#### Scenario: Merge into itself is rejected

- **WHEN** a client calls `POST /api/categories/{A}/merge` with `{"target_category_id":"<A>"}`
- **THEN** the response status is 400

### Requirement: MCP server exposes the task data tools

task-service SHALL expose an MCP server at `POST /mcp` using the Streamable HTTP transport, from which an MCP client can discover and invoke at least the tools `get_tasks`, `get_task_detail`, `create_task`, `create_subtasks`, `update_task`, `update_subtask`, `delete_subtask`, `add_note`, `get_categories`, `create_category` and `update_category`. Tool behavior SHALL match the corresponding REST contract: identical filters, defaults, status-machine validation and cascade deletion. `create_task` SHALL accept `status=draft` and `source=agent`, computing `expires_at` server-side so that the agent can persist a proposal, and `create_subtasks` SHALL create subtasks in batch with `is_ai_generated=true` when the caller requests it.

#### Scenario: Client discovers the tool list

- **WHEN** an MCP client connects to `POST /mcp` and requests the tool list
- **THEN** the response contains all eleven tool names listed in this requirement

#### Scenario: get_tasks applies the status filter

- **WHEN** the agent invokes `get_tasks` with argument `{"status":"completed","category":"市场推广"}`
- **THEN** the tool result contains only completed tasks of that category

#### Scenario: create_task persists an agent draft proposal

- **WHEN** the agent invokes `create_task` with `{"title":"准备产品发布会","status":"draft","source":"agent"}`
- **THEN** the tool result contains the created task identifier and the stored task has status `draft`, source `agent` and a non-null `expires_at`

#### Scenario: create_subtasks marks AI origin

- **WHEN** the agent invokes `create_subtasks` with a task identifier and two subtask payloads
- **THEN** both stored subtasks have `is_ai_generated` true and preserve the requested `sort_order`

### Requirement: Expired proposals cannot be confirmed

Detail queries through REST and MCP SHALL hide expired drafts. Updating an expired draft SHALL return 404 rather than reviving it. Summary cleanup SHALL retain access to underlying rows and convert expired drafts to cancelled.

#### Scenario: Known expired ID cannot bypass visibility

- **WHEN** a client reads or confirms an expired draft by its known identifier
- **THEN** the request returns 404 before exposing or confirming the proposal
