## MODIFIED Requirements

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
