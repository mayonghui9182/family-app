## MODIFIED Requirements

### Requirement: Task resource persistence and retrieval with unified envelope
The system SHALL persist tasks as first-class records with identity, user identifier, title, description, category reference (now a foreign key to categories.id, still nullable to allow "uncategorized" tasks), due date, priority, source, expiry timestamp, workflow status, creation timestamp and last-updated timestamp; every REST endpoint SHALL return a unified JSON envelope where non-zero code indicates a failure and msg describes it. The foreign key SHALL use ON DELETE SET NULL semantics so that a hard delete of a category (not used in normal flows — soft delete is standard) does not cascade-delete tasks.

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