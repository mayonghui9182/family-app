# Proposal: AI 完善档案访谈（PROFILE_INTERVIEW）全链路闭环

## 背景

「档案完善」入口（PersonProfileView → /chat?session_type=PROFILE_INTERVIEW&assigneeProfileId=...）目前跳转后
实际按普通任务拆解会话运行，根因有三层：

1. 前端 `DecomposeChatPageWrapper.vue` 的 `pageInitForm` 未读取 `route.query.session_type`；
   `DecomposeChatPanel.vue` 三处 `store.init` 硬编码 `sessionType: 'TASK_CREATE'`。
2. agent-service 无访谈流程：`decomposer.py` 只有任务拆解链路，从不提取档案补丁、从不调用 B-5 extract-patch。
3. 后端 `GET /sessions/{id}/context` 未暴露 `session_type`，agent 无法感知会话类型。

后端侧 D-1 建会话（session_type=PROFILE_INTERVIEW，标题「AI完善档案访谈」）、D-5 confirm 补丁合并、
D-6 saveProfileOnly、B-5 extract-patch 均已存在且可用，缺的是中间链路。

## 目标

- 前端把 `session_type=PROFILE_INTERVIEW` 从路由 query 透传到 D-1 建会话与 agent 请求体。
- agent-service 新增访谈分支：单轮单次 LLM 调用，输出「回复 + 累计档案补丁 + 是否完成」，
  按 SSE 事件 `message` → `patch_preview` → `proposal` 推送，每轮结束进入 proposing 态，
  复用现有「✅ 确认」「📋 只更新档案」按钮完成补丁落库。
- 补丁经 B-5 extract-patch 持久化到会话；访谈对话经新增 B-7 追加消息端点持久化。
- 后端 session context 暴露 `session_type`，供 agent 判别访谈模式。

## 非目标

- 不改 `AgentProxyController`（C-25 已按原样透传请求体，新增字段自动通过）。
- 不改 D-5/D-6 确认与合并逻辑（沿用现有 proposed_tasks 非空约束与 saveProfileOnly）。
- 不做多档案同时访谈（仅 session.assigneeProfileId / initProfileIdSnapshot 单目标）。
- 不改 mock 链路（decomposeSession.js 中 mock 处于休眠状态）。
