# Spec Delta: profile-interview

## ADDED Requirements

### Requirement: 系统 SHALL 将路由中的 session_type 透传至会话创建与 agent 请求

#### Scenario: 档案完善入口进入 /chat
- WHEN 用户从档案页点击「AI 完善档案」，路由携带 `session_type=PROFILE_INTERVIEW` 与 `assigneeProfileId`
- THEN 前端创建 D-1 会话时 `session_type` 为 `PROFILE_INTERVIEW`，且后续每轮 agent 请求体携带 `session_type`

#### Scenario: 普通任务拆解入口
- WHEN 路由未携带 `session_type` 或值非 `PROFILE_INTERVIEW`
- THEN 会话仍按 `TASK_CREATE` 创建，现有拆解行为不变

### Requirement: agent-service SHALL 在访谈会话中执行访谈流程并推送三类事件

#### Scenario: 访谈轮次响应
- WHEN session context 的 `session_type` 为 `PROFILE_INTERVIEW` 且用户发送一条消息
- THEN agent 单次 LLM 调用产出「回复 + 累计档案补丁 + 是否完成」，并按序推送 SSE 事件 `message`（助手回复）、`patch_preview`（`profileId`/`patch`/`remove_keys`）、`proposal`、`done`

#### Scenario: 补丁字段白名单
- WHEN LLM 产出的补丁包含目标档案类型 schema 之外的字段键
- THEN 后端合并按 profile-schema 白名单校验拒绝，越界键不落库

#### Scenario: 补丁持久化失败
- WHEN 调用 B-5 extract-patch 失败
- THEN 本轮以 `error` 事件终止，不推送 proposal，避免「确认后无补丁可合并」的假成功

### Requirement: 后端 SHALL 暴露会话类型并提供访谈消息追加端点

#### Scenario: agent 获取会话上下文
- WHEN agent 调用 `GET /api/internal/agent/sessions/{id}/context`
- THEN 响应包含 `session_type` 字段（枚举名或 null）

#### Scenario: B-7 追加消息
- WHEN agent 以 X-Internal-Secret 调用 `POST /api/internal/agent/sessions/{id}/messages`，body 为 `{role, content}`，role ∈ {user, assistant}，会话为 ACTIVE
- THEN 消息追加至 session.messages（超过 20 条时裁剪最旧），返回成功

### Requirement: 访谈会话 SHALL 复用既有确认与只更新档案闭环

#### Scenario: 访谈完成后确认
- WHEN 访谈 done=true 后用户点击「✅ 确认」（D-5）
- THEN 后端合并会话 extracted_patch 至目标档案，并按收尾总结任务创建任务（proposed_tasks 非空）

#### Scenario: 只更新档案
- WHEN 任意轮次后用户点击「📋 只更新档案，不生成任务」（D-6）
- THEN 后端合并补丁至目标档案并将会话置为 ARCHIVED，不创建任务
