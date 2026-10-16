## ADDED Requirements

### Requirement: Category resource persistence and retrieval
The system SHALL persist categories as first-class records with identity, user identifier, name, color, icon, sort order, creation timestamp, last-updated timestamp and soft-delete timestamp. The system SHALL expose GET /api/categories returning a unified JSON envelope whose data is an array of non-deleted categories for the current user, ordered by sort_order ascending then id ascending.

#### Scenario: List categories returns empty envelope on fresh database
- **WHEN** a client calls GET /api/categories with no categories in the database
- **THEN** the response status is 200 and the body equals {"code":0,"msg":"success","data":[]}

#### Scenario: List categories ordered by sort_order then id
- **WHEN** three categories exist with sort_order values 2, 1, 2 (and ids 10, 20, 30 respectively)
- **THEN** GET /api/categories returns them ordered as sort_order=1 (id 20), sort_order=2 (id 10), sort_order=2 (id 30)

#### Scenario: List excludes soft-deleted categories
- **WHEN** a category is created and then soft-deleted
- **THEN** GET /api/categories does not include the deleted category in the data array

### Requirement: Category creation validates input, uniqueness and defaults
The system SHALL allow creating a category via POST /api/categories with name (required) and optional color, icon, sort_order. The name SHALL be unique among non-deleted categories of the same user. If color is omitted it SHALL default to #909399. If sort_order is omitted it SHALL default to 0. The user_id SHALL be set to the current user (MVP: default). The response SHALL be a 201 with the created record in data.

#### Scenario: Create with minimal payload defaults color and sort_order
- **WHEN** a client POST /api/categories with body {"name":"家务"}
- **THEN** the response status is 201 and data.id is a non-empty identifier and data.name equals "家务" and data.color equals "#909399" and data.sort_order equals 0 and data.user_id equals "default"

#### Scenario: Create with full payload persists all fields
- **WHEN** a client POST /api/categories with {"name":"工作","color":"#409EFF","icon":"briefcase","sort_order":3}
- **THEN** the response status is 201 and data.name equals "工作" and data.color equals "#409EFF" and data.icon equals "briefcase" and data.sort_order equals 3

#### Scenario: Create rejects duplicate name for same user
- **WHEN** a category with name "家务" exists for user default; client POST /api/categories with {"name":"家务"}
- **THEN** the response status is 400 and envelope code is non-zero with a CATEGORY_NAME_DUPLICATED message

#### Scenario: Create rejects request without name
- **WHEN** a client POST /api/categories with {"color":"#409EFF"}
- **THEN** the response status is 400 and envelope indicates name is required

#### Scenario: Duplicate name of a soft-deleted category is allowed
- **WHEN** a category named "old" was soft-deleted; client POST /api/categories with {"name":"old"}
- **THEN** the response status is 201 and a new category is created with name "old"

### Requirement: Category update supports partial fields and re-checks uniqueness
The system SHALL accept PUT /api/categories/{id} with a partial payload (any subset of name, color, icon, sort_order) and merge changes into the existing record. When name is changed, the uniqueness constraint SHALL be re-evaluated against other non-deleted categories of the same user. Updating a non-existent category SHALL return 404.

#### Scenario: Partial update changes color only
- **WHEN** a category exists with name="家务", color="#909399"; client PUT /api/categories/{id} with {"color":"#F56C6C"}
- **THEN** the response status is 200 and data.color equals "#F56C6C" and data.name is still "家务"

#### Scenario: Update to a name already used by another category is rejected
- **WHEN** categories "A" and "B" exist; client PUT /api/categories/{id of A} with {"name":"B"}
- **THEN** the response status is 400 and envelope code is non-zero with a CATEGORY_NAME_DUPLICATED message

#### Scenario: Update name to its own current name is allowed
- **WHEN** a category "A" exists; client PUT /api/categories/{id} with {"name":"A"}
- **THEN** the response status is 200 and data.name equals "A"

#### Scenario: Update of non-existent category returns 404
- **WHEN** client PUT /api/categories/999999 with {"name":"x"}
- **THEN** the response status is 404 and envelope carries a CATEGORY_NOT_FOUND message

### Requirement: Category soft delete is guarded by task merge requirement
The system SHALL support DELETE /api/categories/{id} performing a soft delete (setting deleted_at). When the category has any non-deleted tasks referencing it, the request SHALL include a mergeTo query or body parameter identifying the target category to which those tasks will be re-pointed. If tasks exist and mergeTo is missing, the request SHALL be rejected with 400. If mergeTo refers to a non-existent or soft-deleted category, the request SHALL be rejected with 400. If the target category is the source itself, the request SHALL be rejected with 400.

#### Scenario: Delete empty category succeeds without mergeTo
- **WHEN** a category exists with no tasks referencing it; client DELETE /api/categories/{id}
- **THEN** the response status is 200 (or 204) and subsequent GET /api/categories excludes the category

#### Scenario: Delete category with tasks but no mergeTo is rejected
- **WHEN** a category has 2 tasks referencing it; client DELETE /api/categories/{id} without mergeTo
- **THEN** the response status is 400 and envelope code is non-zero with a message requiring mergeTo

#### Scenario: Delete category with tasks and invalid mergeTo is rejected
- **WHEN** a category has tasks; client DELETE /api/categories/{id}?mergeTo=999999
- **THEN** the response status is 400 and envelope code is non-zero with a CATEGORY_MERGE_TARGET_INVALID message

#### Scenario: Delete category with tasks and self mergeTo is rejected
- **WHEN** a category with id=5 has tasks; client DELETE /api/categories/5?mergeTo=5
- **THEN** the response status is 400 and envelope code is non-zero with a CATEGORY_MERGE_TARGET_INVALID message

### Requirement: Category merge transfers tasks and soft-deletes source transactionally
The system SHALL support POST /api/categories/{id}/merge with body {"mergeTo": <targetId>} that, in a single transaction, re-points all non-deleted tasks whose categoryId equals the source id to the target id, then soft-deletes the source category. The target SHALL be a non-deleted category belonging to the same user and different from the source. On any failure the entire operation SHALL roll back leaving tasks and source category untouched.

#### Scenario: Merge transfers tasks and removes source
- **WHEN** source category has 3 tasks, target category exists; client POST /api/categories/{sourceId}/merge with {"mergeTo": targetId}
- **THEN** the response status is 200 and the 3 tasks now have categoryId equal to targetId and GET /api/categories no longer includes the source category

#### Scenario: Merge to non-existent target is rejected
- **WHEN** client POST /api/categories/{sourceId}/merge with {"mergeTo": 999999}
- **THEN** the response status is 400 and envelope code is non-zero with a CATEGORY_MERGE_TARGET_INVALID message and no tasks are modified

#### Scenario: Merge to source itself is rejected
- **WHEN** client POST /api/categories/5/merge with {"mergeTo": 5}
- **THEN** the response status is 400 and envelope code is non-zero with a CATEGORY_MERGE_TARGET_INVALID message

#### Scenario: Merge to soft-deleted target is rejected
- **WHEN** target category was soft-deleted; client POST /api/categories/{sourceId}/merge with {"mergeTo": targetId}
- **THEN** the response status is 400 and envelope code is non-zero with a CATEGORY_MERGE_TARGET_INVALID message

### Requirement: Categories are scoped per user
The system SHALL scope all category operations by user_id. List, create, update, merge and delete SHALL only affect categories belonging to the current user. In the MVP the current user is fixed to "default"; the user_id field is reserved for the future family-member account system.

#### Scenario: List returns only categories of the current user
- **WHEN** categories exist for user "default" and (hypothetically) for user "alice"
- **THEN** GET /api/categories returns only the categories whose user_id equals "default"