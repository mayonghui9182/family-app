# category-mgmt Specification

## Purpose
家庭级任务分类管理模块：按 family_id 隔离的一级分类，支持创建/更新/软删除/合并（删除分类必须指定合并到 mergeTo，任务自动搬家）、名称颜色图标排序字段、CHILD 角色 CUD 与合并操作全部 403 拒绝（仅读）、唯一索引按 (family_id, name) WHERE deleted_at IS NULL、可对任务进行按分类过滤与统计，作为家庭日常待办的标签化组织工具。
## Requirements
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

### Requirement: categories 表由 user_id 单用户归属改造为 family_id + created_by 双字段归属，partial unique index 同步替换
The system SHALL, in Flyway V4 migration, replace categories.user_id column with family_id BIGINT NOT NULL REFERENCES families(id) ON DELETE RESTRICT and created_by BIGINT REFERENCES users(id) ON DELETE SET NULL. Existing partial unique index uk_category_user_name SHALL be dropped and replaced with CREATE UNIQUE INDEX uk_category_family_name ON categories(family_id, name) WHERE deleted_at IS NULL. Indexes SHALL also exist on categories(family_id) and categories(created_by).
#### Scenario: V4 migration replaces user_id with family_id + created_by on categories table
- **WHEN** Flyway migrates from V3 to V4 on PostgreSQL 16
- **THEN** psql shows columns family_id NOT NULL, created_by and user_id absent; shows unique index uk_category_family_name on (family_id, name) WHERE deleted_at IS NULL plus idx_categories_family and idx_categories_created_by
#### Scenario: Two families can each own a category with identical name without collision
- **GIVEN** Family F1 has a category named 家务 (id=1, family_id=F1); Family F2 has no such category
- **WHEN** a request creates a new category named 家务 in F2 with valid X-Family-Id=F2 and role=ADULT
- **THEN** creation succeeds (201), new category has family_id=F2; the partial unique index does not reject duplicate names across different families

### Requirement: 分类 CRUD + 合并 API 强制按 X-Family-Id Header 限定家庭作用域，Repository 查询追加 family_id predicate
The system SHALL require the X-Family-Id HTTP header on all /api/categories/** endpoints. AuthInterceptor validates active family membership, 403 FAMILY_NOT_MEMBER if not. CategoryRepository SHALL accept familyId parameter on all find methods: findByIdAndFamilyId(id, familyId), findAllByFamilyIdAndDeletedAtIsNull(familyId), findByNameAndFamilyIdAndDeletedAtIsNull(name, familyId). Merge operation SHALL verify both sourceId and targetId belong to the same family.
#### Scenario: Category list returns only categories of the family in X-Family-Id
- **GIVEN** Family F1 has 4 default categories and Family F2 has 2 custom categories; User U is active member of both
- **WHEN** GET /api/categories is called with X-Family-Id: F2
- **THEN** response data contains exactly 2 categories, all with familyId=F2; none of F1's 4 categories appear
#### Scenario: Category merge of cross-family category IDs is rejected
- **GIVEN** Category C1 belongs to Family F1; Category C2 belongs to Family F2
- **WHEN** POST /api/categories/merge with body sourceId=C1, targetId=C2 and X-Family-Id: F1 is called
- **THEN** response is 404 CATEGORY_NOT_FOUND for C2; no UPDATE or DELETE executes

### Requirement: 分类写权限按角色矩阵控制，CHILD 角色对分类一律只读
The system SHALL enforce role-based access control on category write operations:
- OWNER / ADULT: CREATE / UPDATE name+color / DELETE (soft) / MERGE all allowed
- CHILD: CREATE -> 403 ROLE_NO_PERMISSION 孩子角色不能创建分类; UPDATE -> 403 孩子角色不能修改分类; DELETE -> 403 孩子角色不能删除分类; MERGE -> 403 孩子角色不能合并分类
- All roles: READ endpoints all allowed
#### Scenario: CHILD role attempts CREATE category gets 403
- **GIVEN** User C has CHILD role in family F; valid headers
- **WHEN** POST /api/categories with body name=作业 color=#F56C6C
- **THEN** response 403 ROLE_NO_PERMISSION with Chinese message 孩子角色不能创建分类，请让家长帮忙操作; no row inserted
#### Scenario: ADULT role can MERGE two categories atomically within same family
- **GIVEN** Family F has categories C1 (id=5 name=买菜 color=green) and C2 (id=6 name=购物 color=blue); User A has ADULT role in F
- **WHEN** A calls POST /api/categories/merge sourceId=5 targetId=6 within @Transactional boundary
- **THEN** C1 soft-deleted; all tasks previously with category_id=5 now have category_id=6; response 200 OK

### Requirement: 分类响应 DTO 追加 familyId / createdByName 字段；默认 4 分类种子数据归属演示家庭 family_id=1
The system SHALL extend CategoryResponse to include familyId: Long and createdByName: String. Flyway V4 seed data SHALL insert 4 default categories with family_id=1, created_by=1: 家务/#67C23A, 育儿/#E6A23C, 工作/#409EFF, 财务/#F56C6C; setval categories_id_seq to 4.
#### Scenario: Default 4 categories in family F=1 have correct familyId and createdByName after V4
- **WHEN** a clean PostgreSQL 16 database runs Flyway migrations V1 to V4 to completion
- **THEN** SELECT COUNT(*), family_id FROM categories GROUP BY family_id returns one row (4, 1); GET /api/categories X-Family-Id=1 returns 4 rows with familyId=1 and createdByName=爸爸
#### Scenario: CategoryResponse DTO includes new fields familyId and createdByName
- **GIVEN** Category C in family F with id=3 name=财务 color=#F56C6C created_by=User1 (nickname=爸爸)
- **WHEN** GET /api/categories/3 with X-Family-Id=F
- **THEN** response JSON includes familyId=F.id, createdByName=爸爸 in addition to existing id/name/color/taskCount fields

