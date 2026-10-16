## Why

family-app 目前是纯脚手架状态（无任何业务代码/接口/表）。需要一个最小垂直切片（每日任务 CRUD），以打通端到端全链路：DB schema → Java API → Vue 前端 → Docker 编排，为后续所有功能模块建立工程范式和验证基线。

## What Changes

- 新增 PostgreSQL `tasks` 表，支持任务标题、描述、截止日期、状态、时间戳
- 新增 Java + Spring Boot REST API：任务的 CRUD 5 个端点（POST / GET / GET:id / PUT / DELETE）
- 新增 Vue + Vite 前端最小页面：任务列表 + 创建表单 + 删除操作
- 新增 docker-compose 开发编排：PostgreSQL + 后端 + 前端 3 个服务（Python AI 模块占位容器，不启动）
- 新增 Java 单元测试（针对 CRUD 业务逻辑）、DB migration（含正向 + 回滚）、前端 build 验证脚本

## Non-goals (非目标)

- 任务分类/标签/优先级：后续 change 独立引入
- 任务指派/共享给家庭成员：依赖 family-member 模块，不在本次范围
- 任务提醒/通知/定时调度：依赖 Redis 消息 + 调度器，下次 change
- 任务重复规则（每日/每周循环）：单独建模
- AI 建议模块：Python AI 模块在 docker-compose 中只占位置位，不实现任何 AI 功能
- 用户认证 / 多用户 / 家庭管理：统一在账户体系 change 中引入
- 前端 UI 美化：本次只求链路打通，不做设计/响应式
- 移动端适配/H5：不在本次范围

## Capabilities

### New Capabilities

- `daily-task-mgmt`: 每日任务的完整 CRUD（表结构、REST API、最小 Vue 前端界面），支持创建/查询列表/查详情/更新/删除；通过 docker-compose 一键起开发环境。


### Modified Capabilities

（无，项目无现存 specs）

## Impact

- **代码**：首次引入 3 个代码目录（`backend/` Spring Boot、`frontend/` Vue/Vite、`db/` migration）
- **API**：新增 `/api/tasks` 路径下的 5 个 REST 端点
- **依赖**：
  - backend：Spring Boot (Gradle) + Spring Data JPA + PostgreSQL driver
  - frontend：Vue 3 + Vite + axios
  - db：Flyway or Liquibase migration tool
  - infra：docker-compose yaml
- **系统**：开发环境 3 个容器（Postgres 16 + backend JVM + frontend dev server）
- **数据**：首次有 stateful 组件（Postgres），需约定本地开发时 data volume 路径
- **验证基线**：SOP 阶段6 的 `./gradlew test` / `npm run build` / `docker compose config` / migration 首次被真正使用，若有问题需要回改 Harness 验证表格
