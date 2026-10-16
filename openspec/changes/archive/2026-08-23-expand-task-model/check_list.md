# Check List — expand-task-model

## 1. Spec 完整性
- [x] MODIFIED 块包含主 spec daily-task-mgmt 中所有当前同名 requirement 下的 scenarios（包括 categoryId 6 场景、TODO/DONE 旧名场景的迁移映射）
- [x] ADDED Requirements（Task list multi-dimensional filtering + Draft proposal lifecycle with expiry）每个至少 1 个 scenario
- [x] openspec validate expand-task-model --strict — 无 ERROR

## 2. 代码实施
- [x] TaskStatus 枚举：TODO→PENDING, DONE→COMPLETED，新增 DRAFT（共 5 值）
- [x] TaskPriority 枚举：HIGH / MEDIUM / LOW，默认 MEDIUM
- [x] TaskSource 枚举：MANUAL / AGENT / ROUTINE / SCHEDULE / TRIGGER，默认 MANUAL
- [x] Task 实体新增字段：userId VARCHAR(50) DEFAULT 'default', categoryId BIGINT nullable, priority, source, expiresAt
- [x] DFA 转换矩阵已更新为 5-state：DRAFT→{DRAFT,PENDING,CANCELLED}; PENDING→{PENDING,IN_PROGRESS,COMPLETED,CANCELLED}; IN_PROGRESS→{PENDING,IN_PROGRESS,COMPLETED,CANCELLED}; COMPLETED→{COMPLETED}; CANCELLED→{CANCELLED}
- [x] DRAFT→PENDING 时 clear expiresAt
- [x] TaskRepository 多维过滤 JPQL：status / categoryId / source / keyword (title+description ILIKE) / dueBefore
- [x] TaskController：GET /api/tasks 接受 @RequestParam 过滤参数
- [x] TaskResponse 扩展：返回 priority / source / categoryId / expiresAt / userId
- [x] CreateTaskRequest / UpdateTaskRequest 新增字段 DTO
- [x] Scheduled task：@Scheduled 每 5 分钟扫描 DRAFT 且 expiresAt 过期 → CANCELLED
- [x] ResultCode：必要的错误码

## 3. Flyway V2 迁移
- [x] ALTER TABLE tasks ADD COLUMN user_id/category_id/priority/source/expires_at + DEFAULTs
- [x] UPDATE 旧状态值：TODO→PENDING, DONE→COMPLETED
- [x] DROP 旧 CHECK 约束（chk_tasks_status 以及 tasks_status_check 双候选名，DROP 在 UPDATE 之前执行以避免违反旧约束）
- [x] ADD 新 CHECK 约束 tasks_status_check 包含 5 值 (DRAFT/PENDING/IN_PROGRESS/COMPLETED/CANCELLED)

## 4. 测试
- [x] TaskServiceTest：DFA 5-state 转换（含 DRAFT/PENDING/COMPLETED 直转、终端不可变）
- [x] TaskServiceTest：priority/source 默认值
- [x] TaskServiceTest：DRAFT→PENDING 清 expiresAt
- [x] TaskServiceTest：过期 DRAFT 自动 CANCELLED
- [x] TaskRepositoryTest：多维过滤（status/categoryId/source/keyword/dueBefore 单条件 + AND 组合）
- [x] TaskControllerTest：过滤 query param 端点
- [x] ./gradlew test — 全部 green（expand-task-model 提交时 29+ 测试）

## 5. DB 验证（干净数据卷）
- [x] Flyway 顺序执行 V1→V2：V1 chk_tasks_status 被成功 DROP，status 更新无 violation
- [x] 迁移后 tasks 表含所有新列（user_id/category_id/priority/source/expires_at）
- [x] 存量 TODO/DONE 数据成功迁移为 PENDING/COMPLETED
- [x] 新 CHECK 约束 tasks_status_check 生效（5 值）

## 6. OpenSpec 归档前置
- [x] check_list.md 存在且关键项已勾选
- [x] proposal.md、design.md、tasks.md 存在
- [x] delta spec 头部正确使用 ## MODIFIED / ## ADDED Requirements（无 REMOVED/RENAMED — 此 change 无）