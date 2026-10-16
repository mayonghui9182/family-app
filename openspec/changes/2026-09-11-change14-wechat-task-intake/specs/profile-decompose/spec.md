# Delta Spec: profile-decompose (Change 14 - 微信私聊消息自动对接 AI 拆解)

> 拆解会话此前只能「前端建、前端用」：会话内容只活在浏览器 Pinia，服务端没有按 id 读取的端点。本 delta 补齐 **D-8 服务端读取**，使从微信点进来的全新页面加载能恢复一个草稿。

## ADDED Requirements

### Requirement: 系统 SHALL 提供按家庭作用域读取单个拆解草稿的端点（D-8）

backend SHALL 提供 `GET /api/agent/v1/decompose/sessions/{id}`（`X-Access-Token` + `X-Family-Id`，经 `AuthInterceptor` + `FamilyContextInterceptor`，并受 AI 功能开关约束），返回 `{id, status, initTaskTitle, proposedTasks[], createdTaskIds[]}`。查询 SHALL 带 `family_id` 作用域（硬约束 4），跨家庭访问 SHALL 被拒绝。可读状态 SHALL 至少覆盖 `ACTIVE` 与 `CONFIRMED`（用户重复点击同一确认链接时 SHALL 看到「已创建」的真实状态，而非 404）。`proposedTasks` SHALL 原样透传且不超过既有 `PROPOSED_TASKS_LIMIT`（20）。

#### Scenario: 本家庭读取 ACTIVE 草稿
- **WHEN** 草稿属于当前家庭且 `status=ACTIVE`、已有 3 条 `proposed_tasks`
- **THEN** 返回 200，`initTaskTitle` 为用户原文，`proposedTasks` 长度 3

#### Scenario: 跨家庭读取被拒
- **WHEN** 用另一家庭的登录态请求不属于该家庭的 session id
- **THEN** 请求被拒绝且响应不包含该草稿的标题或任何子任务内容

#### Scenario: 已确认草稿仍可读取
- **WHEN** 草稿已 `CONFIRMED` 且 `created_task_ids` 有 3 个 id
- **THEN** 返回 200 且 `createdTaskIds` 非空，前端据此展示「已创建 3 个任务」

#### Scenario: AI 开关关闭
- **WHEN** AI 功能开关关闭时调用 D-8
- **THEN** 返回 50310 业务码，与本能力其它端点行为一致

### Requirement: 服务端 SHALL 能在无前端会话态的情况下独立驱动一次拆解

拆解的会话状态 SHALL 完全可由服务端持久化列承载：草稿标题/用户原文来自 `init_task_title`、拆解结果来自 `proposed_tasks`、生命周期来自 `status`。系统 SHALL NOT 依赖 `messages` JSONB 列（该列在既有实现中仅被初始化为空数组、生产路径从未写入），也 SHALL NOT 为本能力向该列引入写入语义。

#### Scenario: 仅凭服务端数据渲染详情页
- **WHEN** 前端以全新页面加载（无 Pinia 历史）读取 D-8 响应
- **THEN** 「你说：…」与待办清单两个区块均可仅由 `initTaskTitle` + `proposedTasks` 渲染完成

#### Scenario: 死列保持为空
- **WHEN** 一条草稿经由微信入站被创建并完成拆解
- **THEN** 该 session 的 `messages` 仍为空数组，`extracted_profile_patch` 仍为空对象
