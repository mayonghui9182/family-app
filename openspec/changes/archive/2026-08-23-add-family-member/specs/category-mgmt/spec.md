# Spec: 分类管理（增量变更）

**Capability id**: category-mgmt（对 add-family-member 变更而言为 MODIFIED 能力）

**Change**: add-family-member

---

## ADDED Requirements

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

