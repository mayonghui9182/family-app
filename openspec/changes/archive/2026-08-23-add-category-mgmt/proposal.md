## Why

`expand-task-model` 已为 Task 加上 `categoryId` 字段（nullable，未建表）。要真正实现按分类组织任务、过滤查询和可视化分组，必须提供分类的独立管理能力。当前任务仅靠标题前缀或人工记忆归类，无法支撑日常事务分类（如"家务/育儿/工作/财务"）和多用户家庭的协作场景。本变更落地 `categories` 表与 CRUD API，使任务的 `categoryId` 真正可用，并为后续 subtask、note、rule-template 提供组织维度。

## What Changes

- **新增 categories 表**：字段 `id`、`name`（必填，同用户下唯一）、`color`（hex 色值，默认 `#909399`）、`icon`（可选，emoji 或图标字符串）、`sort_order`（int，默认 0）、`user_id`（VARCHAR(50)，MVP 固定 `default`）、`created_at`、`updated_at`、`deleted_at`（软删除标记，nullable）
- **新增分类 CRUD API**：
  - `GET /api/categories` — 列出当前用户未删除的分类（按 sort_order、id 排序）
  - `POST /api/categories` — 创建分类
  - `PUT /api/categories/{id}` — 更新名称/颜色/图标/排序
  - `DELETE /api/categories/{id}` — 软删除；若分类下有任务，强制要求传 `mergeTo`（目标分类 id）合并任务后删除，未传或目标不存在则拒绝
- **新增合并分类能力**：`POST /api/categories/{id}/merge` body `{ mergeTo: <targetId> }` — 将源分类下所有未删除任务的 `categoryId` 改写为目标分类，再软删源分类（事务保证）
- **启用 Task.categoryId 关联**：Flyway V3 迁移为 `tasks.category_id` 加 FK 约束（`ON DELETE SET NULL`，配合软删除仍为 SET NULL 以避免硬删误伤）；查询接口 `/api/tasks?categoryId=` 已在 expand-task-model 实现，本变更确保引用完整性
- **默认分类种子数据**：V3 迁移插入 4 条默认分类（`家务`、`育儿`、`工作`、`财务`），user_id=`default`，sort_order 1-4
- **统一响应信封**：所有分类 API 返回 `Result<T>`，错误码新增 `CATEGORY_NOT_FOUND`、`CATEGORY_NAME_DUPLICATED`、`CATEGORY_MERGE_TARGET_INVALID`

## Capabilities

### New Capabilities

- **category-mgmt**：分类的增删改查、合并、排序，按用户隔离。包含软删除、名称唯一约束、合并转移任务的完整生命周期管理。

### Modified Capabilities

- **daily-task-mgmt**：Task.categoryId 字段从"仅字段无关联"升级为"FK 关联到 categories 表"，任务的分类引用完整性由数据库约束保证。

## Non-goals

- 不实现分类的层级结构（父子分类/树形）——后续独立 change
- 不实现分类的批量导入/导出
- 不实现分类的颜色主题预设库（仅支持 hex 自定义）
- 不实现分类的拖拽排序 API（仅支持 sort_order 字段手动设置）
- 不修改前端 UI——独立 change add-frontend-category
- 不修改 Task 已有的过滤查询逻辑（expand-task-model 已实现 `categoryId` 过滤）
- 不实现多用户体系——user_id 仍固定 `default`，由后续 add-family-member 变更引入
- 不修改已归档的 add-daily-task-mgmt 变更内容

## Impact

- **数据库**：Flyway V3 迁移脚本——`CREATE TABLE categories`（含唯一约束 `uk_user_name (user_id, name) WHERE deleted_at IS NULL`）、`ALTER TABLE tasks ADD CONSTRAINT fk_tasks_category` 外键、插入 4 条默认分类
- **后端 Java 代码**：
  - 新增包 `com.familyapp.category`：`Category` 实体、`CategoryRepository`、`CategoryService`/`CategoryServiceImpl`、`CategoryController`、DTO（`CreateCategoryRequest`、`UpdateCategoryRequest`、`CategoryResponse`、`MergeRequest`）
  - 复用 `Result<T>` 信封与全局异常处理器
  - `TaskRepository` 无需改动（过滤查询已支持 categoryId）
- **现有测试**：无破坏性改动；新增 CategoryService 测试套件覆盖 CRUD、合并、唯一约束、软删除
- **API 兼容性**：纯新增，无 BREAKING 变更；Task.categoryId 字段语义从 nullable 升级为 FK 引用（nullable 保留，用于"未分类"任务）