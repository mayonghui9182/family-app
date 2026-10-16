## Context

`expand-task-model` 已为 Task 加上 `categoryId` 列（nullable，无 FK），但 `categories` 表尚未创建，分类能力缺失。本变更落地分类的独立管理：建表、CRUD、合并、软删除、默认数据，并启用 Task.categoryId 的外键约束，使任务可按分类组织、过滤和可视化分组。

前端 TaskView 已有基础 CRUD，过滤参数 `?categoryId=` 已在 expand-task-model 实现，但无分类管理入口。本变更只覆盖后端，前端 UI 由独立 change add-frontend-category 承接。

参见 proposal.md 了解动机和变更范围，specs/ 了解需求场景。

## Goals / Non-Goals

**Goals:**
- 创建 `categories` 表（含软删除、用户隔离、同用户名称唯一）
- 实现分类 CRUD API（GET/POST/PUT/DELETE）+ 合并接口（POST /merge）
- 启用 `tasks.category_id` FK 约束（ON DELETE SET NULL）
- service 层校验 categoryId 引用存在性，返回友好错误
- Flyway V3 迁移：建表 + FK + 4 条默认分类种子数据
- 新增 CategoryService 测试套件覆盖 CRUD、合并、唯一约束、软删除、FK 校验

**Non-Goals:**
- 不实现父子分类/树形层级（后续独立 change）
- 不实现拖拽排序 API（仅支持 sort_order 字段手动设置）
- 不修改前端 UI（独立 change add-frontend-category）
- 不实现多用户体系（user_id 固定 default，由 add-family-member 引入）
- 不修改 Task 已有过滤查询逻辑
- 不修改已归档的 add-daily-task-mgmt 变更内容

## Decisions

### D1: 唯一约束使用 partial unique index

**选择**: 在 categories 表上创建 `CREATE UNIQUE INDEX uk_category_user_name ON categories(user_id, name) WHERE deleted_at IS NULL`，配合 service 层预校验给出友好错误。

**替代方案**:
- 普通唯一约束 `(user_id, name)`: 软删除后无法重建同名分类
- 仅应用层校验无 DB 约束: 并发场景下可能产生重复

**理由**: partial index 只对未删除记录生效，允许"软删后重建同名"。同时 service 层 try-catch 唯一约束异常或预查询，返回 `CATEGORY_NAME_DUPLICATED` 友好错误码。PostgreSQL 原生支持 partial index，无额外依赖。

### D2: 合并操作使用 @Transactional 保证原子性

**选择**: `POST /api/categories/{id}/merge` 在 CategoryServiceImpl 上用 `@Transactional` 标注方法，依次执行：`UPDATE tasks SET category_id = mergeTo WHERE category_id = sourceId AND deleted_at IS NULL`，再 `UPDATE categories SET deleted_at = now WHERE id = sourceId`。任一失败整体回滚。

**替代方案**:
- 拆成两个独立 API（先转移任务再删分类）: 客户端需协调两步，中间态不一致
- 使用数据库触发器: 跨平台性差，Spring 生态不推荐

**理由**: 单事务保证合并原子性。操作数据量小（家庭场景分类任务数有限），单事务性能可接受。Spring `@Transactional` 是项目既有模式（TaskServiceImpl 已用）。

### D3: FK 使用 ON DELETE SET NULL 而非 RESTRICT

**选择**: `ALTER TABLE tasks ADD CONSTRAINT fk_tasks_category FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE SET NULL`

**替代方案**:
- `ON DELETE RESTRICT`: 硬删分类时若有任务引用则阻止。但软删除是标准流程，硬删不应发生
- `ON DELETE CASCADE`: 会级联删除任务，绝不可接受

**理由**: 标准流程是软删除（设 deleted_at）+ 合并转移任务，软删不触发 FK。FK 仅作为兜底：万一发生硬删，SET NULL 把任务降级为"未分类"而非数据丢失，比 RESTRICT 更安全（RESTRICT 会阻塞运维操作）。引用完整性在 service 层校验（D4）。

### D4: categoryId 存在性在 service 层校验

**选择**: TaskService 在 create/update 时，若 categoryId 非空，先 `categoryRepository.findByIdAndUserId` 校验存在且未软删，不存在则抛 `CATEGORY_NOT_FOUND`（400）。

**替代方案**:
- 仅依赖 DB FK 约束: 报错变成 500 + SQLException，无友好消息
- 在 Task 实体上加 `@ManyToOne` 关联: 引入懒加载/急加载决策，当前 Task 无关联关系，保持简单

**理由**: service 层校验给出 400 + 业务错误码，符合统一响应信封规范。DB FK 作为兜底防止脏数据。不引入 JPA 关联关系避免过度设计，categoryId 以 plain Long 存储，查询时按需 join。

### D5: 默认分类在 V3 迁移脚本插入

**选择**: Flyway V3 脚本在建表 + FK 后，`INSERT INTO categories` 插入 4 条默认分类（家务/育儿/工作/财务），user_id=default，sort_order 1-4。

**替代方案**:
- 应用启动时 ApplicationRunner 插入: 不可重入，重启重复插入需判重
- 手动 API 调用初始化: 部署步骤多，易遗忘

**理由**: Flyway 迁移幂等且只执行一次，与表结构变更同脚本保证一致性。默认分类是 MVP 基线数据，放迁移脚本最可靠。

### D6: 软删除使用 deletedAt 字段（与 Task 一致）

**选择**: Category 实体含 `deletedAt`（Instant, nullable），所有查询默认 `WHERE deleted_at IS NULL`，复用项目既有软删除模式。

**替代方案**:
- 物理删除: 丢失历史，无法审计
- boolean is_deleted 字段: 无时间戳，不如 deletedAt 信息丰富

**理由**: 与 Task 实体的软删除模式完全一致，降低认知成本。deletedAt 提供删除时间，便于排查。

### D7: user_id 硬编码 default，预留 future

**选择**: CategoryService 从 `SecurityContext` 或配置中取当前 user_id，MVP 阶段固定返回 `default`，所有查询/写入都带 `WHERE user_id = 'default'`。

**替代方案**:
- 不加 user_id 字段: 未来引入多用户需迁移
- 现在就接入鉴权: 范围蔓延，add-family-member 才引入账号体系

**理由**: 字段预留避免未来迁移，service 层用一个 `getCurrentUserId()` 方法返回 default，未来切换到真实鉴权时只改一处。与 Task.userId 策略一致。

## Risks / Trade-offs

- **[partial index 兼容性]** -> PostgreSQL 原生支持 partial index。项目用 PostgreSQL（docker-compose），无兼容风险。若未来切换到不支持 partial index 的数据库，需回退到应用层判重。
- **[合并事务性能]** -> 单事务内 UPDATE 所有相关任务。家庭场景单分类任务数 <1000，性能可接受。若未来任务量大，可分批处理。
- **[FK ON DELETE SET NULL 兜底]** -> 若运维误执行硬删 `DELETE FROM categories`，任务 categoryId 被置空而非报错。可接受：比数据丢失好，且标准流程是软删不会触发。
- **[默认分类 user_id=default 硬编码]** -> 未来多用户时默认分类仅属于 default 用户，新用户需自行创建或扩展迁移。由 add-family-member 变更处理。

## Migration Plan

1. 执行 V3 迁移脚本（Flyway 自动执行）：建 categories 表 + partial index + tasks FK + 插入 4 条默认分类
2. 部署更新后的后端代码（新增 com.familyapp.category 包 + TaskService categoryId 校验）
3. 运行 CategoryService 测试套件
4. 验证 GET /api/categories 返回 4 条默认分类

**回滚策略**:
- 代码层: git revert
- 数据库层: 手动执行反向 SQL（DROP INDEX uk_category_user_name; ALTER TABLE tasks DROP CONSTRAINT fk_tasks_category; DROP TABLE categories;）。Flyway 社区版不支持自动回滚。

## Open Questions

（无——所有设计决策已参照 expand-task-model 既有模式确认）