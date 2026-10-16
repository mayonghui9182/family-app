# Design: AI 完善档案访谈全链路

## 总体流程（每轮对话）

```
用户在 /chat 输入一句话
  → store.sendUserMessage → _realSseStream POST /api/agent/v1/decompose/start {message, session_id}
  → AgentProxyController C-25 原样透传 body → agent-service /api/v1/chat/stream
  → decomposer.stream(session_id=...) 取 GET /sessions/{id}/context（新增 session_type 字段）
  → 若 session_type == PROFILE_INTERVIEW：进入访谈分支
      1. B-3 get_profiles 按 assignee_profile_id 过滤出目标档案（含 profileType/profileData）
      2. 渲染 prompts/interview.j2（目标档案 + 该类型 schema 字段白名单 + 对话历史 + 已有补丁）
      3. 单次 LLM 调用（json_object + LLMInterviewResponse + parse_interview）
      4. SSE 依次推：message(助手回复) → patch_preview{profileId, patch, remove_keys} → proposal → done
      5. 持久化：B-5 extract-patch 存累计补丁；B-7 追加用户消息与助手回复到 session.messages
  → proposal 事件令 store 进入 proposing 态，UI 出现「✅ 确认」「📋 只更新档案，不生成任务」
  → 用户点确认(D-5)：后端合并 extracted_patch 到档案（applyExtractedPatchToLinkedProfiles）
  → 用户点只更新档案(D-6)：无需任务，直接合并并 ARCHIVED
```

## 关键决策

1. **单轮单次 LLM 调用**：reply + 累计 patch + done 标志一次产出，避免多段调用超时（30s→56001）。
2. **每轮都发 proposal**：store 的 confirmProposal/saveOnlyProfile 均要求 `status==='proposing' && proposal`，
   因此每轮结束必须推 proposal。未完成时 proposal 为「访谈进行中」占位摘要任务（用户一般不会点确认）；
   done=true 时 proposal 为收尾总结任务（≥1 条，满足 D-5 confirm 的 proposed_tasks 非空约束）。
3. **补丁是累计的**：LLM 每轮输出到目前为止的全部补丁字段（含 remove_keys），B-5 覆盖式保存，
   避免增量合并复杂度。
4. **代理零改动**：C-25 `MAPPER.writeValueAsBytes(body)` 原样转发，前端 body 加 session_type 即可；
   agent 侧以 context 的 session_type 为准（前端 body 的 session_type 仅作兜底提示）。
5. **schema 白名单进提示词**：按 profileType（BABY/ELDER/ADULT/PET/OTHER）从
   `backend/src/main/resources/schemas/profile-schema.json` 提炼字段清单硬编码进 interview.j2 注释区，
   LLM 只允许输出白名单内字段键，越界键由后端 applyProfilePatch 校验兜底拒绝。
6. **B-7 新端点**：`POST /api/internal/agent/sessions/{id}/messages` body `{role, content}`，
   X-Internal-Secret 鉴权，仅 ACTIVE 会话，messages 上限沿用 20 条裁剪（与 B-6 一致）。

## 改动点清单

### backend（2 处）
- `InternalAgentController.getContext`：`data.put("session_type", s.getSessionType()==null?null:s.getSessionType().name())`。
- `InternalAgentController` 新增 B-7 appendMessage（校验 role∈{user,assistant}，content 非空截断，cap 20）。

### agent-service（5 处）
- `models/api_schemas.py`：`ChatStreamRequest` 增加 `session_type: str | None = None`。
- `models/llm_schemas.py`：新增 `LLMInterviewResponse{reply, patch, remove_keys, profile_id, done}`。
- `harness/json_guard.py`：新增 `parse_interview(raw)`（56002 模式）。
- `connectors/backend_client.py`：新增 `save_extracted_patch(session_id, extracted_patch)`（B-5，无 family 参数）、
  `append_session_message(session_id, role, content)`（B-7）。
- `prompts/interview.j2` 新增；`services/decomposer.py` 增加访谈分支（context.session_type 判定，
  早于 Step1 分类），事件顺序 message → patch_preview → proposal → done；B-5/B-7 持久化失败不阻断流
  （记 warning，补丁仍可经 proposal 的 profile_patch 由前端兜底？——不，前端无兜底，故 B-5 失败时报错终止本轮，
  返回 error 事件，保证「确认即合并」语义可靠）。

### frontend（2 处）
- `DecomposeChatPageWrapper.vue` pageInitForm 增加 `sessionType: String(route.query?.session_type || 'TASK_CREATE')`。
- `DecomposeChatPanel.vue`：三处 init 改为 `sessionType: props.initForm?.sessionType === 'PROFILE_INTERVIEW' ? 'PROFILE_INTERVIEW' : 'TASK_CREATE'`；
  访谈模式文案（标题「AI 完善档案访谈」、输入占位、按钮提示）；idle 态进入访谈模式时自动以引导语启动首轮。

## 风险与缓解

- LLM 输出越界字段键 → 后端 applyProfilePatch schema 校验拒绝并报错，agent 提示词明确白名单。
- done 判定过松（一轮就结束）→ 提示词要求至少覆盖核心字段后才置 done=true。
- B-7 写失败导致历史缺失 → 降级：agent 内存中本轮仍可用 context.messages（后端已有 B-6 路径），
  B-7 失败记 warning 不终止。
- 老人用户理解成本 → 访谈 UI 文案大字、口语化，按钮沿用既有「只更新档案」明确语义。

## 验证

- agent：pytest 新增访谈分支单测（mock backend_client + mock LLM），验证事件序列与 B-5/B-7 调用。
- backend：`./mvnw -q compile` + 相关 InternalAgentController 测试（若存在则跑，不存在至少编译过）。
- frontend：`npm run build` 通过；手工链路验证由用户在移动端确认。
