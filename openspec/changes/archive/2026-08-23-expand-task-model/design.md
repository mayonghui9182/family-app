## Context

当前后端有一个基本的 Task CRUD 实现（add-daily-task-mgmt 归档变更），包含 Task 实体、4 值状态枚举（TODO/IN_PROGRESS/DONE/CANCELLED）、DFA 转换矩阵、软删除、统一响应包装。前端有一个简单的 TaskView 组件。

设计文档要求将任务模型扩展为支持 draft 提案流程、优先级分级、来源追踪、分类关联和多维过滤。这些是后续 subtask/category/note/template 等模块的前置依赖。

参见 proposal.md 了解动机和变更范围。

## Goals / Non-Goals

**Goals:**
- 将 TaskStatus 从 4 值扩展到 5 值（新增 DRAFT，重命名 TODO->PENDING, DONE->COMPLETED）
- 新增 TaskPriority 和 TaskSource 枚举
- Task 实体新增 userId/categoryId/priority/source/expiresAt 字段
- 实现多维过滤查询（status/categoryId/source/keyword/dueBefore）
- 实现 DRAFT 生命周期：自动设置 24h 过期、定时扫描过期、确认时清空 expiresAt
- Flyway V2 迁移：DDL 变更 + 存量数据转换

**Non-Goals:**
- 不创建 categories 表（后续 add-category-mgmt 变更）
- 不实现 subtask/note/template/schedule/trigger（独立变更）
- 不修改前端 UI（独立前端变更）
- 不实现 AI/agent-service（Python 服务延后）
- 不修改软删除逻辑

## Decisions

### D1: 过滤查询使用 JPQL @Query

**选择**: 在 TaskRepository 上使用 @Query JPQL + nullable 参数模式

**替代方案**:
- JPA Specifications: 类型安全，但 5 个过滤参数各需一段 Predicate 样板代码，增加复杂度
- QueryDSL: 最流畅，但引入新依赖 + APT 构建插件，过度设计

**理由**: 仅 5 个固定过滤参数，JPQL :param IS NULL 模式足够清晰。如果后续过滤需求增长，可重构到 Specifications。@Query 可读性更好，改动集中在一个 Repository 文件。

### D2: DRAFT 过期使用 @Scheduled 内嵌

**选择**: 在 TaskServiceImpl 上使用 @Scheduled(fixedDelay = 300000)（5 分钟间隔）扫描过期 DRAFT 并更新为 CANCELLED

**替代方案**:
- 查询时过滤（WHERE status != DRAFT OR expires_at > now()）: 简单但数据永远不干净
- 懒加载更新（读取时检查并更新）: 每次读取有额外检查和写操作
- 独立 DraftExpiryService + Quartz: 职责分离，但当前规模不需要

**理由**: @Scheduled 内嵌在 TaskServiceImpl 在当前规模下足够。5 分钟间隔平衡了及时性和负载。单实例部署（Docker Compose）不需要分布式锁。

### D3: categoryId 加列不加 FK

**选择**: V2 迁移添加 category_id 列但不加 FK 约束，业务层在 categoryId 非空时校验

**替代方案**:
- 延迟到 add-category-mgmt 变更一起做: Task 实体设计不完整
- 本变更就创建 categories 表 + FK: 范围蔓延

**理由**: 列存在但 FK 延后到 categories 表创建时添加。categoryId 非空时由 service 层校验（但当前 categories 表不存在，所以 categoryId 只能是 null）。这保证了数据模型完整性的同时不扩大变更范围。

### D4: PENDING -> COMPLETED 允许直接跳转

**选择**: DFA 允许 PENDING 直接转到 COMPLETED

**替代方案**:
- 强制经过 IN_PROGRESS: 设计文档的原始意图，但家庭场景中简单任务不需要"进行中"阶段

**理由**: 用户确认家庭场景中有些任务（如"买个牛奶"）直接完成即可，不需要中间状态。保留 IN_PROGRESS 作为可选中间状态。

### D5: DRAFT -> PENDING 时清空 expiresAt

**选择**: 状态转换 DRAFT -> PENDING 时，将 expiresAt 设为 null

**替代方案**:
- 保留原值作为历史记录: TaskResponse 中会有困惑的 expiresAt 值

**理由**: 确认后的任务不再有"过期"概念，清空更语义清晰。

### D6: 枚举值在 DB 中存储大写

**选择**: Java 枚举名直接存入 DB（DRAFT, PENDING, IN_PROGRESS, COMPLETED, CANCELLED）

**替代方案**:
- 存储小写匹配设计文档: 但 Java 枚举约定是大写，现有代码也用大写

**理由**: 与现有代码一致（TODO/IN_PROGRESS/DONE/CANCELLED 都是大写）。设计文档的小写只是 API 文档风格，DB 和 Java 层用大写。

### D7: V2 迁移包含存量数据转换

**选择**: 在同一个 V2 迁移脚本中执行 DDL + DML（UPDATE status）

**替代方案**:
- 分两个迁移脚本（V2 DDL + V3 DML）: 过度拆分，Flyway 事务保证原子性

**理由**: Flyway 在 PostgreSQL 中每个迁移脚本运行在事务中，DDL + DML 要么全部成功要么全部回滚。

## Risks / Trade-offs

- **[BREAKING API 变更]** -> 状态字符串从 TODO/DONE 改为 PENDING/COMPLETED，已部署客户端需更新。MVP 阶段无外部客户端，风险可控。
- **[迁移失败]** -> V2 脚本包含 DDL + DML，Flyway 事务保证原子性。回滚策略: 恢复 V1 状态需要手动执行反向 UPDATE + DROP COLUMN。
- **[@Scheduled 多实例问题]** -> 如果未来部署多实例，多个 @Scheduled 会重复执行。当前单实例 Docker Compose 部署，不构成问题。未来可用 ShedLock 解决。
- **[categoryId 无 FK 约束]** -> 数据完整性依赖业务层。当前 categoryId 只能是 null（categories 表不存在），无实际风险。

## Migration Plan

1. 执行 V2 迁移脚本（Flyway 自动执行）
2. 部署更新后的后端代码
3. 现有测试更新为新的状态枚举值
4. 验证存量数据已正确迁移（TODO->PENDING, DONE->COMPLETED）

**回滚策略**:
- 代码层: git revert
- 数据库层: 手动执行反向 SQL（PENDING->TODO, COMPLETED->DONE, DROP COLUMN）
- Flyway 不支持自动回滚（社区版），需手动操作

## Open Questions

（无——所有设计决策已在 brainstorming 阶段确认）
