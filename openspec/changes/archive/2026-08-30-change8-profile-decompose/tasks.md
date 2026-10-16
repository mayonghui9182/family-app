# Tasks: 人员档案 + AI 沟通式拆解（Change 8）

> 执行顺序：Task 1 → Task 2 → Task 3（顺序依赖，不可并行；每 Task 完后跑对应测试全绿再进下一步）

> **收尾状态（2026-09-05，master 416a17f 合并后回填）**
> - Task 1 ✅ 全量交付（V9 迁移 + 档案 CRUD + Internal B 组 + assignee 过渡）
> - Task 2 ✅ Java 侧交付（D-1~D-7 会话端点 + S-1 建议，落点偏差见 check_list #4 + 50311/50312/50313）；**Step 3 Python A-3/A-4 未实施**——合并确认的交付形态为前端 Mock SSE（事件契约 1:1），Python 真链路列为后续 change
> - Task 3 ✅ 组件/store/api/路由交付（DecomposeChatPanel/PersonProfileView/ProfileCard/ProfileEditorDialog/SuggestionsPanel + decomposeSse 6 态机）；**Step 1 规划的 6 个组件单测未编写**，列为后续 change
> - 收尾 ✅ 2026-09-05：check_list 20 项证据回填 + 全量回归（Java 230/0/0、agent 85/95.19%、vitest 58、build ✓；含本轮修复 DevTestBootstrapSeedRunner H2 序列回归）+ 归档

- [x] **Task 1：V9 数据迁移 + 人员档案后端 CRUD + Internal B 组**
  - **Step 1：TDD 失败测试（先写测试后实现）**
    - V9MigrationTest：H2 + PG 真机 flywayClean+Migrate 两张表+索引成功（2 用例）
    - ProfileSchemaTest：profile-schema.json 与白名单表一致；4 类 key+非法值 全部拦截（≥12 用例）
    - PersonProfileControllerTest：P-1~P-6 CRUD + CHILD 40303 + 非OWNER删40304 + 待办绑定删40901（8 用例）
    - ProfilePatchTest：D-5/D-6 白名单校验：超范围 key discard + 类型不匹配 discard + 合法 merge 成功（≥8 用例）
  - **Step 2：写 profile-schema.json SSOT** → backend/src/main/resources/schemas/profile-schema.json（§1.2 白名单完整版）
  - **Step 3：V9 Flyway 迁移** backend/src/main/resources/db/migration/V9__Add_Person_Profiles_And_Decompose_Sessions.sql
    - person_profiles 表 + 4 索引（见 §1.1）
    - tasks.assignee_profile_id 列 + 索引（见 §1.3）
    - agent_decompose_sessions 表 + 3 索引（见 §1.4）
    - rule_templates.profile_types VARCHAR(10)[] 列 DEFAULT NULL
  - **Step 4：afterMigrate 扩展**
    - V9 迁移后老家庭档案初始化 SQL 段：遍历活跃 family_members 反查 users.birthday 建档案
    - afterMigrate__agent_reader.sql 追加 GRANT SELECT(id, family_id, name, profile_type, profile_data, gender, birthday, relation, tags) ON person_profiles TO agent_reader；GRANT SELECT(assignee_profile_id) ON tasks TO agent_reader
  - **Step 5：Java 代码**
    - 新包 com.familyapp.profile：PersonProfile(实体)/Repository/Service(含白名单校验)/Controller(P-1~P-6) + DTOs(ProfileOption/PersonProfileResponse/Create/UpdateRequest) + ProfileDataSchema(读 JSON) + ProfileRoleGuard
    - ResultCode 追加 40303/40304/40901/40010/42201 五条中文消息
    - Task 实体 + DTO 改造：Task 加 assigneeProfileId 字段 + 实体关联；TaskResponse 加 assigneeProfile；CreateTaskRequest 加 assigneeProfileId 字段 + Service 兼容回填逻辑（§1.3）
    - FamilyService.createFamily 末尾自动创建 owner 档案（ADULT 型）
    - InternalAgentController 扩展：B-3 get-by-family / B-4 占位 501 / B-5 extract-patch / B-6 save-proposal
    - WebConfig/SecurityConfig 放行清单更新：/api/agent/v1/suggestions 跳过 50310 AI 开关守卫但保留 token+familyId
  - **Step 6：运行 Step 1 写的测试 → 全 PASS**
    - Java：./gradlew test 全部 ≥ 原有 189+tests（V9 相关新增用例 ≥30 条）
  - **Step 7：Commit feat(change8/task1): V9迁移 + 档案CRUD + B组 + 过渡assignee**

- [x] **Task 2：会话 + 建议 + agent-service 扩展（D-1~D-7 + S-1 + A-3/A-4）** <!-- Java 侧全量交付；Step 3 Python A-3/A-4 DEFERRED（前端 Mock SSE 为合并交付形态），详见顶部收尾状态与 check_list #10~12 -->
  - **Step 1：TDD 失败测试**
    - SuggestionApiTest(S-1)：3 数组上限；profile+category 4.1A；profile-only 正确；LIMIT 10/10/20（5 用例）
    - DecomposeAtomicTest(D-5)：第 3 条 task save 抛异常 → 前 2 条回滚 + 档案未变 + COUNT=0（1 用例）
    - DecomposeAbandonTest(D-7)：前后 person_profiles/tasks COUNT 快照相等（1 用例）
    - DecomposeSessionControllerTest(D-1~D-7)：7 端点 200 + 权限 + 终态不可继续 50311（≥12 用例）
    - ProfileChatPromptTest(python)：profile_chat.j2 相位 asking->extracted_patch->done 流程 + suggest_options 上限 + 白名单 key（3 用例）
    - DecomposeWithProfilesTest(python)：decompose.j2 注入 profiles，生成的 assignee_profile_id 必须来自 profiles 列表（2 用例）
    - test_profile_chat_flow.py(python)：A-3/A-4 422 边界 + B-3/B-5/B-6 httpx mock 调用成功（5 用例）
  - **Step 2：Java 代码**
    - 新 DecomposeSession(实体)/Repository/DecomposeSessionService(D-5 @Transactional 原子写) / DecomposeSessionController(D-1~D-7)
    - AgentProxyController S-1：SuggestionService 三查询 + DTO，跳过 guardAiEnabled()
    - AgentProxyController D-2/D-4 StreamingResponseBody 转发 SSE，复用 C-31 的 SSE copy 逻辑
    - 错误码追加 50311/50312/50313
  - **Step 3：agent-service Python 代码**
    - 新文件 models/profile_schema.py（读 profile-schema.json）
    - 新文件 prompts/profile_chat.j2（§3.3 严格输出 JSON）
    - 升级 prompts/decompose.j2（§3.4 注入 profiles_full）
    - connectors/backend_client.py：新增 get_profiles_by_family / extract_session_patch / save_session_proposal
    - routes/chat_routes.py：新增 A-3 profile-chat/stream、A-4 decompose/with-profiles/stream
    - services/decomposer.py：Step 4 LLM 调用升级为 with-profiles 模式
    - tests 补齐 Step 1 的 python 用例
  - **Step 4：测试全 PASS**
    - Java：./gradlew test ≥ baseline + 新 20+ 用例
    - Python：python3 -m pytest -q ≥ 原 92+ 新 10 用例
  - **Step 5：Commit feat(change8/task2): 会话D1~D7 + 建议S1 + agent A3A4扩展 + 新Prompt**

- [x] **Task 3：前端 5 组件 + TaskView 改造 + 路由 + 老人友好 + 联调验收** <!-- 组件/store/api/路由全交付（SSE 为 Mock 实现）；Step 1 的 6 个组件单测 DEFERRED，详见 check_list #8/#13~16 -->
  - **Step 1：TDD 失败测试**
    - TaskViewDropdown.test.js：指派下拉 P-6 + 切换家庭下拉刷新（2 用例）
    - SuggestionsPanel.test.js：3 Tab 渲染 + 点击建议回填表单（3 用例）
    - DecomposeChatPanelAbandon.test.js：cancelled phase 未调 profiles.save/tasks.create；spy 断言 0 次（2 用例）
    - DecomposeChatPanelConfirm.test.js：confirmed 后刷新 profilesStore + tasksStore 列表；assertion(创建任务数 match)（2 用例）
    - PersonProfileView.test.js：网格/搜索/过滤/新建弹框/AI访谈弹框（4 用例）
    - ProfileEditorDialog.test.js：profile_type 切换动态字段 + 白名单校验前端丢弃超范围 key（2 用例）
  - **Step 2：Pinia stores**
    - stores/profiles.js：actions list/profile(CRUD)/options/refresh；缓存当前家庭全档案 + options 下拉列表；监听 familyStore.current 变化自动 refresh
    - stores/decomposeSse.js 改造：新增 phase 状态机(idle/chatting/proposing/confirmed/archived/cancelled/error)；D-1 新建会话；D-2 SSE 事件 typing/message/patch_preview/done 处理；D-4 SSE reasoning/proposal 处理；D-5 confirm / D-6 save-only / D-7 abandon 动作方法
  - **Step 3：API 封装**
    - api/profiles.js（P-1~P-6）
    - api/suggestions.js（S-1）
    - api/decomposeSession.js（D-1~D-7，D-2/D-4 走 fetch+ReadableStream 复用 utils/sseReconnect.js 现有重连逻辑）
  - **Step 4：组件/视图**
    - PersonProfileView.vue（4.2）：卡片网格 + 搜索 + 类型过滤 + 详情抽屉 + 编辑/删除/AI访谈
    - ProfileCard.vue：emoji+名字+类型+关系+年龄+标签+按钮
    - ProfileEditorDialog.vue：5 大类型卡片 + profile-schema.json 动态字段
    - SuggestionsPanel.vue：3 Tab + 数量徽标 + 三类点击行为回填/跳/看
    - DecomposeChatPanel.vue（4.5）：6 状态机 + SSE + suggest_options 按钮条 + patch_preview 绿卡 + proposal 内联编辑卡（老人手改 AI 提案）
  - **Step 5：TaskView.vue 改造（5 处）**
    - 指派下拉 / 分类下拉 / 双按钮行 / 建议面板 / taskId 或 文本拆解 入口
    - Task 卡 assignee 显示位替换为 assigneeProfile（新列优先，旧列兜底）
  - **Step 6：路由/AppSider/Menu**
    - router/index.js：新增 /profiles（requiresAuth）
    - AppSider.vue：菜单新增「👥 成员档案」
  - **Step 7：老人友好 4 检查项（手动 visual + aria-labels）**
    - 输入框/按钮 size=large；emoji+中文；颜色对比；建议项按钮条 <=6 个
  - **Step 8：全量测试 PASS**
    - frontend：npx vitest run ≥ 原 48+ 15 新用例
    - 构建：frontend npm run build no-error
  - **Step 9：Commit feat(change8/task3): 5Vue组件 + TaskView改造 + 路由菜单 + 老人友好**

- [x] **收尾：Change 8 验收 + CheckList 填证实 + OpenSpec归档 + handoff 交接**
  - Step 1：Change 8 §5 验收 10 条逐条跑，每条把实际测试命令输出贴 check_list.md Evidence 列
  - Step 2：./gradlew test + pytest + vitest 全量 3 条命令；截图/日志保存到 handoffs/current.md
  - Step 3：同步 handoffs/current.md 最新 Change 8 状态 + Next actions
  - Step 4：通知用户审阅；用户确认后再走 openspec-archive（不移到 archive 前保留在 changes/根目录）
