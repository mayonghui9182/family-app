# Proposal: 工作任务计划系统 MVP（自学习任务拆解核心闭环）

## Why

工作任务（市场推广、工作汇报等场景）的拆解目前依赖人工经验，拆出来的步骤质量不稳定，且「拆得好不好」的反馈没有沉淀机制。`bus_docs/task-planner-design/task-planner-design.html` 已产出完整设计（v1.0，86 项决策）：Agent 基于历史已办任务和规则模板拆解任务，用户审查确认，每日总结以「用户修改 vs AI 原始生成」的 diff 为学习信号自动更新模板，形成越用越准的闭环。本变更将该设计的 **MVP 核心拆解闭环** 以仓库内独立子项目 `task-planner/` 的形式立项落地；family-app 现有 AI 拆解面向家庭多租户场景，与本项目的单用户工作场景在数据、模板、学习机制上均不共用，故独立建设而非扩展既有服务。

## What Changes

- 新增独立子项目 `task-planner/`：独立 docker-compose 编排、独立 PostgreSQL 数据库（`task_db`）、单用户 MVP（无认证，数据模型预留 `user_id` 字段），与 family-app 主应用互不影响。
- 新增 **task-service**（Python FastAPI，有状态）：
  - 任务 / 子待办 / 注意事项 / 分类 / 规则模板（decomposition 型）+ 模板版本历史的 REST CRUD；
  - draft 提案生命周期：创建、用户确认（draft→pending）、24h 过期自动 cancelled、查询过滤；
  - MCP Server（`/mcp`，Streamable HTTP）：向 agent-service 暴露任务读写、分类、模板、历史 few-shot、当前负载、总结执行日志等工具。
- 新增 **agent-service**（Python FastAPI，无状态）：
  - `POST /api/chat`：SSE 流式拆解（分类 → 模板匹配 → few-shot 参考 → 拆解 → 经 MCP 创建 draft 任务+子待办）；
  - 每日总结（22:00 APScheduler 定时 + `POST /api/summary/trigger` 手动触发）：按分类对比学习，生成模板更新并写回；
  - LLM 采用 DeepSeek（OpenAI 兼容接口），JSON 模式 + 解析容错 + 超时降级。
- MVP 入口为 HTTP/SSE 会话接口（可用 curl / 脚本直接对话）；React 前端与 CLI 客户端不在本变更内。

## Capabilities

### New Capabilities

- `task-planner/task-mgmt`: 任务/子待办/注意事项/分类的数据管理与 draft 提案生命周期，含 MCP Server 数据访问工具契约、删除与过期策略。
- `task-planner/ai-decomposition`: 会话式 AI 拆解——SSE 事件流、LLM 分类、模板匹配、历史 few-shot、负载提示、冷启动、JSON 容错与超时降级。
- `task-planner/template-learning`: decomposition 规则模板与版本历史、每日总结对比学习（用户修改 diff → 模板更新建议）、手动触发、失败重试、用户手动编辑优先的冲突策略。

### Modified Capabilities

（无。不改动 family-app 既有 spec：`category-mgmt`、`daily-task-mgmt`、`family-mgmt`、`frontend-ui` 的需求均不受影响；task-planner 为独立部署的平行子系统。）

## Non-goals（非目标）

- **三类自动化触发**（routine 日常模板 / task_schedules 一次性定时 / task_triggers 依赖触发）及 02:00 日常任务生成调度：设计文档已覆盖，另立后续变更。
- **React 前端**（Dashboard/Chat/Tasks/Templates/Categories 等 8 页）与 **CLI 客户端**：MVP 通过 SSE/REST 接口直接会话，界面后置。
- **tool-service 天气能力**：仅 routine 天气条件需要，随自动化触发变更一并落地。
- **多用户、认证、family_id 租户隔离、微信生态集成**：本子系统为单用户 MVP，仅预留 `user_id` 字段。
- **修改 family-app 现有服务**（backend / agent-service / frontend / wechat-service / tool-service）的任何代码或数据库。
- **routine 型模板的 conditions 字段管理**：数据模型按设计文档预留 `type` 枚举，但 MVP 只实现 decomposition 型的行为。

## AI 数据流（输入 / 模型 / 输出）

**拆解链路（POST /api/chat）**
- 输入：用户消息；经 MCP 拉取的分类列表、分类下 decomposition 模板（is_active=true）、同分类已办任务 few-shot（历史「任务 → 子待办」样例）、当前负载（未完成数 + 优先级分布 + 即将到期数）。
- 模型：DeepSeek `deepseek-chat`，三段 LLM 调用——①分类（消息+分类列表→分类，无匹配归「未分类」，不自动建分类）；②模板匹配（任务描述+模板摘要→选模板，均不匹配则冷启动硬拆）；③拆解（消息+分类+模板+few-shot+负载→子待办 JSON，2-8 条），全程 `response_format: json_object`。
- 输出：经 MCP `create_task(status=draft)` + `create_subtasks` 落库；SSE 事件流 `classification → reasoning(逐块) → proposal(完整 JSON) → done`，失败发 `error` 事件；draft 带 `expires_at`（24h）。

**每日总结链路（22:00 / 手动触发）**
- 输入：当天 0:00 至执行时刻的新建/完成/修改任务，按分类分组，每分类最多 10 条有修改行为的任务（优先修改幅度大者）；核心信号为「AI 原始生成子待办 vs 用户修改后子待办」的 diff（不可变 `ai_snapshot` 快照，`is_ai_generated` 仅保留来源标记）；当前分类模板内容。
- 模型：DeepSeek，每分类一次调用（串行），超时 60s，单分类失败跳过不影响其他分类。
- 输出：`template_updates` JSON（`add_step` / `remove_step` / `update_estimated_count` / `update_notes`，均带 reason）+ `new_notes`；同一调整模式出现 ≥2 次才更新模板；经 MCP 写回模板（version+1，写入版本历史与 change_summary）；执行结果记入 `summary_execution_log`；过期 draft 标记 cancelled。

## Impact

- **新增代码**：`task-planner/task-service/`、`task-planner/agent-service/`、`task-planner/docker-compose.yml`、`task-planner/.env.example`、`task-planner/README.md`（目录结构遵循设计文档 §14）。
- **数据库**：新增独立 PostgreSQL 容器与 `task_db`（9 张表中 MVP 落地 7 张：tasks、subtasks、notes、categories、rule_templates、rule_template_versions、summary_execution_log；task_schedules、task_triggers 随自动化变更再建）。Alembic 管理迁移。
- **依赖**：FastAPI、SQLAlchemy、Alembic、APScheduler、MCP Python SDK（Server + Client / Streamable HTTP）、DeepSeek API（外部）。
- **端口与编排**：task-planner compose 使用独立 project name 与端口段，避免与 family-app 既有 compose（含 production/tls 覆盖层）冲突。
- **配置/密钥**：新增 `POSTGRES_*`、`LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL`、`MCP_TASK_SERVICE_URL`、`AGENT_SERVICE_URL` 等，经 `.env` 注入，不入库不入 git。
- **既有系统**：零影响——不修改 family-app 任何服务、数据库与治理文件。
