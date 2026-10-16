## 1. Database Migration (V3)

- [x] 1.1 Create `backend/src/main/resources/db/migration/V3__Add_Category_Mgmt.sql`
- [x] 1.2 CREATE TABLE categories with columns: id BIGSERIAL PK, name VARCHAR(50) NOT NULL, color VARCHAR(20) NOT NULL DEFAULT '#909399', icon VARCHAR(50), sort_order INT NOT NULL DEFAULT 0, user_id VARCHAR(50) NOT NULL DEFAULT 'default', created_at TIMESTAMP NOT NULL DEFAULT now(), updated_at TIMESTAMP NOT NULL DEFAULT now(), deleted_at TIMESTAMP
- [x] 1.3 CREATE UNIQUE INDEX uk_category_user_name ON categories(user_id, name) WHERE deleted_at IS NULL
- [x] 1.4 ALTER TABLE tasks ADD CONSTRAINT fk_tasks_category FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE SET NULL
- [x] 1.5 INSERT INTO categories 4 default rows: 家务/#67C23A/1, 育儿/#E6A23C/2, 工作/#409EFF/3, 财务/#F56C6C/4

## 2. Domain Layer (com.familyapp.category)

- [x] 2.1 Create Category.java entity matching table columns (id, name, color, icon, sortOrder, userId, deletedAt, createdAt, updatedAt); use @Entity + @Table(name = "categories")
- [x] 2.2 Create CategoryRepository.java extending JpaRepository<Category, Long> with methods: findByUserIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc, findByIdAndUserIdAndDeletedAtIsNull, existsByUserIdAndNameAndDeletedAtIsNull
- [x] 2.3 Add updatedAt handling consistent with Task entity pattern
## 3. DTO Layer (com.familyapp.category.dto)

- [x] 3.1 Create CreateCategoryRequest.java: name (@NotBlank), color (optional), icon (optional), sortOrder (optional Integer)
- [x] 3.2 Create UpdateCategoryRequest.java: name, color, icon, sortOrder all optional (partial merge); validate at least one field present in service
- [x] 3.3 Create CategoryResponse.java with static fromEntity(Category) mapping all fields to JSON-safe shapes (Instant to ISO string, consistent with TaskResponse)
- [x] 3.4 Create MergeRequest.java: mergeTo (@NotNull Long)

## 4. Service Layer (com.familyapp.category)

- [x] 4.1 Create CategoryService.java interface: list(), create(CreateCategoryRequest), update(Long id, UpdateCategoryRequest), delete(Long id, Long mergeTo), merge(Long sourceId, MergeRequest req)
- [x] 4.2 Implement CategoryServiceImpl.list(): getCurrentUserId, call findByUserIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc, map to response
- [x] 4.3 Implement create(): validate name not duplicate (existsByUserIdAndNameAndDeletedAtIsNull), apply defaults (color #909399, sortOrder 0), save, catch DataIntegrityViolationException as fallback for concurrent dup
- [x] 4.4 Implement update(): find by id+userId+not-deleted (404 if missing), partial-merge non-null fields, if name changed re-check uniqueness, save
- [x] 4.5 Implement delete(id, mergeTo): find category (404 if missing); if no tasks reference it, soft-delete; if tasks exist and mergeTo null then 400; if mergeTo equals id then 400 CATEGORY_MERGE_TARGET_INVALID; if mergeTo target missing/deleted then 400; else delegate to merge semantics
- [x] 4.6 Implement merge(sourceId, MergeRequest): @Transactional; validate source and target distinct, target exists and not deleted; taskRepository bulk update UPDATE tasks SET category_id = targetId WHERE category_id = sourceId AND deleted_at IS NULL; then soft-delete source category
- [x] 4.7 Add private getCurrentUserId() helper returning "default" (single point of change for future auth)
- [x] 4.8 Add ResultCode entries: CATEGORY_NOT_FOUND, CATEGORY_NAME_DUPLICATED, CATEGORY_MERGE_TARGET_INVALID

## 5. Task Service Integration

- [x] 5.1 Update TaskServiceImpl.create(): if request.categoryId non-null, validate via categoryRepository.findByIdAndUserIdAndDeletedAtIsNull(categoryId, userId) then throw CATEGORY_NOT_FOUND if missing
- [x] 5.2 Update TaskServiceImpl.update(): if request.categoryId is present (key exists in payload), validate when non-null; if explicitly null, clear the field
- [x] 5.3 Inject CategoryRepository into TaskServiceImpl (constructor injection)
## 6. Controller Layer (com.familyapp.category)

- [x] 6.1 Create CategoryController.java with @RequestMapping("/api/categories"): GET to list(); POST to create(@Valid @RequestBody CreateCategoryRequest); PUT /{id} to update(id, @Valid @RequestBody UpdateCategoryRequest); DELETE /{id} to delete(id, @RequestParam(required=false) Long mergeTo)
- [x] 6.2 Add POST /{id}/merge endpoint to merge(id, @Valid @RequestBody MergeRequest)
- [x] 6.3 All endpoints return Result envelope via existing Result.success(data) / Result.error(...) helpers

## 7. Tests

- [x] 7.1 CategoryServiceTest: list empty on fresh DB; list ordered by sortOrder then id; list excludes soft-deleted
- [x] 7.2 CategoryServiceTest: create with minimal payload defaults color/sortOrder; create with full payload; create rejects duplicate name; create rejects missing name; create allows name of soft-deleted category
- [x] 7.3 CategoryServiceTest: partial update changes single field; update to duplicate name rejected; update to own name allowed; update non-existent returns 404
- [x] 7.4 CategoryServiceTest: delete empty category without mergeTo succeeds; delete category with tasks without mergeTo rejected; delete with invalid mergeTo rejected; delete with self mergeTo rejected
- [x] 7.5 CategoryServiceTest: merge transfers tasks and soft-deletes source; merge to non-existent rejected; merge to self rejected; merge to soft-deleted target rejected
- [x] 7.6 TaskServiceTest: create with valid categoryId persists; create with missing categoryId rejected; create without categoryId persists null; update categoryId to valid/invalid/null
- [x] 7.7 Controller smoke test: GET /api/categories returns default 4 categories after V3 migration

## 8. Verification

- [x] 8.1 Run full backend test suite: ./gradlew test — all green (63 tests, 0 failures)
- [x] 8.2 Start backend via docker-compose, curl http://localhost:8080/api/categories returns 4 default categories in Result envelope
- [x] 8.3 Verify V3 migration applied: psql \d categories shows table + index + FK (flyway_schema_history V1+V2+V3 all success=t)
- [x] 8.4 Run openspec validate add-category-mgmt --strict — passes