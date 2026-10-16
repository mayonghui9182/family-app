## 1. Enum Changes

- [x] 1.1 Update TaskStatus enum: rename TODO to PENDING, DONE to COMPLETED, add DRAFT value
- [x] 1.2 Create TaskPriority enum (HIGH, MEDIUM, LOW)
- [x] 1.3 Create TaskSource enum (MANUAL, AGENT, ROUTINE, SCHEDULE, TRIGGER)

## 2. Entity and DTO Expansion

- [x] 2.1 Update Task entity: add userId (String default "default"), categoryId (Long nullable), priority (TaskPriority), source (TaskSource), expiresAt (Instant nullable)
- [x] 2.2 Update CreateTaskRequest: add priority, source, categoryId fields (all optional String/Long)
- [x] 2.3 Update UpdateTaskRequest: add priority, source, categoryId fields (all optional)
- [x] 2.4 Update TaskResponse.fromEntity(): add priority, source, categoryId, expiresAt, userId to response

## 3. DFA and Service Logic

- [x] 3.1 Update TRANSITIONS map: DRAFT to {DRAFT,PENDING,CANCELLED}, PENDING to {PENDING,IN_PROGRESS,COMPLETED,CANCELLED}, IN_PROGRESS to {PENDING,IN_PROGRESS,COMPLETED,CANCELLED}, COMPLETED to {COMPLETED}, CANCELLED to {CANCELLED}
- [x] 3.2 Update create(): default status PENDING, reject CANCELLED and COMPLETED, allow DRAFT, auto-set expiresAt = now plus 24h when status is DRAFT
- [x] 3.3 Update update(): clear expiresAt to null on DRAFT to PENDING transition, merge priority/source/categoryId partial updates
- [x] 3.4 Add parsePriorityOrDefault and parseSourceOrDefault helper methods
- [x] 3.5 Add @Scheduled expireDrafts() method: find DRAFT tasks with expiresAt before now, set to CANCELLED, 5 minute interval

## 4. Repository and Controller

- [x] 4.1 Add findByStatusAndExpiresAtBefore(TaskStatus, Instant) to TaskRepository
- [x] 4.2 Add @Query findWithFilters(status, categoryId, source, keyword, dueBefore) JPQL with nullable params to TaskRepository
- [x] 4.3 Update TaskController.list() with @RequestParam for status, categoryId, source, keyword, dueBefore
- [x] 4.4 Update TaskServiceImpl.list() to accept filter params and delegate to findWithFilters()

## 5. Database Migration

- [x] 5.1 Create V2__Expand_Task_Model.sql: ALTER TABLE tasks ADD user_id/category_id/priority/source/expires_at, UPDATE status TODO to PENDING and DONE to COMPLETED, update CHECK constraint. Rollback: reverse UPDATE plus DROP COLUMN.

## 6. Error Codes

- [x] 6.1 Add INVALID_PRIORITY and INVALID_SOURCE to ResultCode enum

## 7. Scheduling Configuration

- [x] 7.1 Add @EnableScheduling to FamilyAppApplication class

## 8. Test Updates

- [x] 8.1 Update existing tests: replace all TODO assertions with PENDING, DONE with COMPLETED
- [x] 8.2 Add DFA tests: DRAFT to PENDING, DRAFT to CANCELLED, PENDING to COMPLETED direct, COMPLETED terminal rejection
- [x] 8.3 Add filtering tests: status filter, keyword LIKE, multi-filter AND, dueBefore with null exclusion
- [x] 8.4 Add DRAFT lifecycle tests: create draft with auto expiresAt, confirm clears expiresAt, expired draft excluded from list
- [x] 8.5 Add new fields tests: create with priority/source/categoryId, partial update priority and source

## 9. Build Verification

- [x] 9.1 Run gradlew build and gradlew test, confirm all tests pass
- [x] 9.2 Run openspec validate --specs, confirm delta spec still valid
