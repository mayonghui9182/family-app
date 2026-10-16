# Tasks: 2026-09-26-profile-interview

## 后端
- [x] T1 `InternalAgentController.getContext` 暴露 `session_type`
- [x] T2 新增 B-7 `POST /api/internal/agent/sessions/{id}/messages`（role/content 校验，cap 20）
- [x] T3 后端编译验证（`./gradlew compileJava`，本仓库为 Gradle 非 Maven）

## agent-service
- [x] T4 `ChatStreamRequest` 增加可选 `session_type`
- [x] T5 `LLMInterviewResponse` + `parse_interview`
- [x] T6 `BackendClient.save_extracted_patch`（B-5）与 `append_session_message`（B-7）
- [x] T7 新增 `prompts/interview.j2`（按 profileType 输出字段白名单，对齐 profile-schema.json）
- [x] T8 `decomposer.py` 访谈分支：message → patch_preview → proposal → done，B-5/B-7 持久化
  - 修正：B-5 payload 必须为扁平行格式 `{profile_id, <字段>:值..., remove_keys:[]}`（后端 `mergeOneProfile` 剥掉结构键后把顶层字段合并进 profile_data；嵌套 `{"patch":{...}}` 会被整体丢弃）
- [x] T9 pytest 访谈单测（`tests/test_interview_flow.py` 4 例；全量 100 passed，覆盖率 92.86%）

## 前端
- [x] T10 `DecomposeChatPageWrapper.vue` pageInitForm 透传 `sessionType`（含访谈标题/返回档案页/跳过 resume 拦截）
- [x] T11 `DecomposeChatPanel.vue` 访谈模式 UI：header/idle/chatting/proposing/终态文案 + `startInterview` 自动首轮 + 隐藏演示与「确认生成待办」入口
- [x] T12 `npm run build` 验证（✓ built in 4.66s）

## 收尾
- [x] T13 更新 `.handoffs/current.md` 与 `handoffs/current.md`
