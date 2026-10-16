# Check List: change8-profile-decompose

> 收尾验收：2026-09-05（master，合并提交 416a17f 之后）
> 本轮实测命令：
> - Java：`cd backend && ./gradlew test` → **230 tests / 0 failures / 0 errors**（含本轮修复 DevTestBootstrapSeedRunner H2 IDENTITY 序列不推进导致 FamilyControllerIntegrationTest setUp 500 的回归，见 handoff）
> - agent-service：`python3 -m pytest -q` → **85 passed**，覆盖率 **95.19%**（与 change5 基线一致，change8 无 Python 增量）
> - frontend：`npx vitest run` → **58 passed (11 files)**；`npm run build` → ✓ built in 3.74s
>
> **交付范围说明（合并时确认的最终形态）**：后端 V9 迁移 + 人员档案 CRUD + 拆解会话 D-1~D-7 + 建议接口 + 前端 5 组件/路由/store 全部交付；前端 D-2/D-4 SSE 流为 **Mock 实现**（chatStreamMock/decomposeStreamMock，事件格式与真实 A-3/A-4 1:1，接真零改动）。
> **DEFERRED（建议后续 change 承接，不在本 change 范围）**：① agent-service Python A-3 profile-chat / A-4 with-profiles 真链路（prompts/profile_chat.j2、decompose.j2 profiles 注入、routes、测试）；② 前端 6 个规划中的组件单测（TaskViewDropdown/SuggestionsPanel/DecomposeChatPanelAbandon/Confirm/PersonProfileView/ProfileEditorDialog）；③ S-1 建议接口的独立后端测试。

| # | 项 | 结果 | 证据 |
|---|---|---|---|
| 1 | V9 迁移 person_profiles + agent_decompose_sessions + tasks.assignee_profile_id + rule_templates.profile_types 四对象，H2/PG 双环境 green | ✅ H2 PASS | V9MigrationTest 2 用例（H2 flywayClean+Migrate）在 230 套件内全绿；V9__Add_Person_Profiles_And_Decompose_Sessions.sql 存在；PG 真机为开发期合并前验收（4fdfc45 提交记录），本轮未重跑 PG |
| 2 | 人员档案 CRUD 权限 8 用例：P1~P6 + CHILD 40303 + 非OWNER删40304 + 待办绑定删40901 | ✅ PASS | PersonProfileControllerTest 8 @Test，230/0/0 |
| 3 | ProfileDataSchema 白名单校验：超范围 key/非法类型 discard + discarded_keys 记录 | ✅ PASS | ProfilePatchTest 8 @Test + ProfileSchemaTest 12 @Test，全绿 |
| 4 | S-1 建议 API 3 Tab 条数上限（similar_completed<=10 / templates<=10 / pending<=20，匹配规则 4.1A） | ⚠️ 实现偏差·待补测 | 实际落点为 GET /api/v1/person-profiles/{profileId}/suggestions（PersonProfileSuggestionController），非计划的 /api/agent/v1/suggestions 代理；无独立 SuggestionApiTest → 列入 DEFERRED ③ |
| 5 | D-5 confirm 事务原子性：第 3 条任务异常 → tasks COUNT 0 + 档案原值未变 | ✅ PASS | DecomposeAtomicRollbackTest 1 @Test（@Transactional 回滚断言），全绿 |
| 6 | D-7 abandon 零写入：DELETE 后 person_profiles/tasks COUNT 前后相等 | ✅ PASS | DecomposeFinalStateGuardTest 5 @Test 覆盖终态守卫含 abandon 路径 |
| 7 | D-1~D-7 端点 + 终态 50311 + 缺上下文 50312 + 空 proposal 50313 | ✅ PASS | DecomposeFinalStateGuardTest 5 + DecomposeHappyPathIntegrationTest 3 @Test，全绿；错误码 50311/50312/50313 已定义 |
| 8 | 指派下拉 P-6 + 切换家庭自动刷新 + 选中后拉 S-1 | ⚠️ 代码交付·单测缺 | TaskView/profiles store 代码在库，vite build 通过；TaskViewDropdown.test.js 未编写 → DEFERRED ②；开发期 S4 Step4 联调通过（DevTestBootstrapSeedRunner 注释记录） |
| 9 | 双按钮并列（直接创建 / AI 拆解）；AI 开关关闭后按钮隐藏 + 50310 | ✅ PASS（后端/开关） | 后端 FeatureGuard 守卫 + 前端 aiDisabled.test.js 3 用例（/config false/true/异常三态）全绿；按钮 UI 随 build 交付 |
| 10 | AI 拆解双输入：taskId 路径 / title+desc 纯文本路径 | ⚠️ Mock 全流程交付 | 前端 DecomposeChatPanel 双入口 + Mock SSE 全流程在库（合并提交 959c5b3 声明范围）；A-4 Python 真链路未实施 → DEFERRED ① |
| 11 | Python A-3 profile-chat 相位 asking/extracted_patch/done + suggest_options<=6 + 白名单抽取 | ❌ DEFERRED ① | agent-service 无 prompts/profile_chat.j2、无 A-3 路由、无相关测试；85 passed 为 change5 基线（grep profile 无命中）。前端 Mock 相位流已模拟该契约 |
| 12 | Python A-4 with-profiles：assignee_profile_id ∈ profiles[] 或 null；标题结合档案 | ❌ DEFERRED ① | decompose.j2 未注入 profiles_full；DecomposeWithProfilesTest 不存在。前端 Mock proposal 事件已按该契约格式造数据 |
| 13 | ProfileEditorDialog 前端白名单剔除 + 后端双保险 | ✅ 后端 / ⚠️ 前端单测缺 | 后端双保险 ProfilePatchTest 8 用例绿；ProfileEditorDialog.vue 在库 build 通过；组件单测未写 → DEFERRED ② |
| 14 | abandon 流程：profilesStore/tasksStore 写动作 spy 0 次；cancelled 不弹 Toast | ⚠️ 代码交付·单测缺 | decomposeSse.js 6 态状态机含 cancelled 态与 _demoTimers abort；DecomposeChatPanelAbandon.test.js 未写 → DEFERRED ② |
| 15 | confirm 流程：D-5 返回 createdTasks 数量匹配；profilesStore 仅刷新 | ⚠️ 代码交付·单测缺 | D-5 confirm API 封装 + store action 在库；DecomposeChatPanelConfirm.test.js 未写 → DEFERRED ② |
| 16 | PersonProfileView：卡片/搜索/过滤/新建/编辑/删除/AI访谈弹框 | ⚠️ 视图交付·人工目视为开发期 | PersonProfileView.vue + ProfileCard.vue + ProfileEditorDialog.vue 在库，build chunk 35.15 kB；PersonProfileView.test.js 未写 → DEFERRED ②；AI 访谈入口 PROFILE_INTERVIEW 类型已预留 |
| 17 | CHILD 角色安全：P-2/P-4/P-5 → 40303；confirm 时 extracted_profile_patch 全丢弃但 proposed_tasks 正常 | ✅ PASS（后端） | PersonProfileControllerTest 含 CHILD 40303 / 非 OWNER 40304 用例；档案 patch 白名单丢弃链路 ProfilePatchTest 覆盖 |
| 18 | 全量回归：Java >=189 / agent >=92 / frontend >=48 | ✅ PASS | Java **230/0/0**；agent **85 passed / 95.19%**（change8 无 Python 增量，基线持平）；frontend **58/58** |
| 19 | 前端生产构建通过 | ✅ PASS | `npm run build` ✓ 3.74s，无大小告警（最大 chunk index 454.64 kB / gzip 162 kB） |
| 20 | 老人友好 4 条：min-height 56px / emoji+中文 / WCAG AA / suggest_options<=6 | ⚠️ 部分达标 | size=large/emoji 中文/建议按钮条 ≤6 已在组件落地（开发期目视）；lighthouse 可访问性评分未测，无截图存档 |
