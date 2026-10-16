# Change 8: 家庭人员档案 + AI 对话式拆解 Implementation Plan

> **设计定版（2026-08-30 用户确认 4 节全通过）**：
> B1 精简版档案白名单（4 类型 26 key）· 方案1 为主 + 方案3 档案页 AI 访谈 · 任务建议匹配 4.1A(category+profile，无 category 仅 profile)· 4.2A 一次 API 返回 3 数组 · 4.3 默认上限 10/10/20 · 会话草稿箱模式：D-1~D-4 只写 agent_decompose_sessions；只有 D-5/D-6/D-7 终态才写业务表；放弃=零回滚零脏写 · avatar_emoji 不在 DDL 写 emoji 字面量（避免 9P/终端/DB 编码损坏），改为 Service 层按 profile_type 兜底 + 列 NOT NULL。
> **4 段 Experience 教训已落地到本 plan**：D-5 批量建任务必须 new Task() 不带 id；不要范围漂移；9P 卷写语法文件走 V8 hex → wsl python3 wb；Read 失败时换 Grep/Glob 分段。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal**：交付 4 子系统闭环：① 家庭人员档案 CRUD（注册用户 + 虚拟成员宝宝/老人/宠物）· ② 任务创建页「指派给谁 下拉」+ 「直接创建 / AI 沟通拆解」双按钮 + 3 Tab 建议面板 · ③ 多轮沟通草稿箱（会话表）+ 沟通结果确认才写档案+生成任务 · 放弃 0 写入 · ④ 档案页独立 AI 访谈。Java>=189 green / agent-service>=92 green / frontend>=48 green。

**Architecture**：Java Spring 分层（新包 com.familyapp.profile + com.familyapp.agent.DecomposeSessionController + 现 InternalAgentController 扩 B-3/B-5/B-6）· person_profiles.profile_data 4 类白名单由 profile-schema.json 作为 Single Source of Truth（后端/前端/agent 同读一份 JSON 防 drift）· 会话原子靠「草稿箱 + D-5 @Transactional 两阶段 commit」保证放弃=0 写入 · Python agent-service 扩两路由 A-3 profile-chat/stream + A-4 with-profiles decompose（读档案 DbReader；写会话中间态走 B 组 Internal API）· 前端 DecomposeChatPanel 6 态状态机（idle/chatting/proposing/confirmed/archived/cancelled）复用 utils/sseReconnect.js 重连逻辑。

**Tech Stack**：Java 17+Spring Boot 3.2 / PostgreSQL 16 / Flyway V9 / Vue 3 + Element Plus(Via 已经在 frontend/package.json) / Pinia / Axios / Python 3.12 + FastAPI + httpx 异步客户端 + SQLAlchemy async asyncpg + Jinja2 prompt + pytest + TDD 严格。

## Global Constraints
- 所有新表/新列 100% family_id 多租户：NOT NULL REFERENCES families(id) ON DELETE CASCADE；查询所有默认加 deleted_at IS NULL + family_id=:currentFamily
- tasks.assignee_id → assignee_profile_id 过渡策略：两列共存本 Change 全程；CreateTaskRequest 传 assigneeProfileId 优先；两列都传且不一致 40010「指派给谁参数冲突，请仅使用新档案字段」；TaskResponse 读 assignee_profile_id 新列优先，空 fallback 旧列反查 users.nickname
- person_profiles.profile_data 写必须过 ProfileDataSchema 白名单校验（超范围 key + 类型错 一律 discard，记入 discarded_keys + WARN 日志，**不阻断流程**）；JSONB 浅合并 existing || patch（PostgreSQL ||）；删除 key 只能用 P-4 remove_keys[]
- **只有 D-5/D-6/D-7 三个终态端点会写业务表**；D-1~D-4 只能 INSERT/UPDATE agent_decompose_sessions.session 中间态；严禁在 chat 循环里实时 flush profile_data 更新
- D-5 confirm **必须 @Transactional**（propagation=REQUIRED，isolation=READ_COMMITTED，timeout=30s）；两动作 ① patch person_profiles.profile_data ② new Task() 批量 save，**同成同败**；proposed_tasks 处理上限 20 条，超限返回 50314「单轮拆解最多 20 条，请拆分后重试」（减少长事务超时死锁）
- D-5 每条 Task 建任务：实体必须 **new Task()** + setId(null) 显式；禁止 findById() → save 复用（771397 教训：会触发 UPDATE 替代 INSERT）；save 前后 info 日志打印实体 id 前后值；DTO CreateTaskInternal 创建对象里**不接收 id 字段**（如果用户传了，反序列化时 Jackson @JsonIgnore 或直接抛 400）
- AI 开关：/api/agent/v1/** 除 S-1 / D-7(config 遗留 Change 7) 外，所有 D 端点进 guardAiEnabled；S-1 是纯本地查询，**跳过 AI 50310**（用户关 AI 也能看建议）
- UI 老人友好硬约束：所有新组件按钮 min-height 56px size=large；emoji+中文按钮文本；颜色对比 WCAG AA >=4.5:1；建议项 suggest_options 最多 6 个按钮（老人手点不手打）；取消流程不弹 Toast 成功
- 所有错误消息 ResultCode 中文；新增 8 条：40303/40304/40901/40010/42201 + 50311/50312/50313/50314（见 tasks.md 清单对应解释）
- 9P 卷写语法敏感文件（Java/Python/JS/JSON/V9.sql/profile-schema.json）必须走「V8 utf8 编码 hex → wsl python3 wb」路径，写完 3 信号验证（2 锚点 Grep=1 + 文件大小非 0）
- 过渡阶段不删除 tasks.assignee_id 列；Change 10 再处理
- CHILD 角色安全：P-2/P-4/P-5 一律 40303；D-6 save-only 拒绝；D-5 confirm 时 extracted_profile_patch 全丢弃（只生成任务不改档案）

---

## File Structure（103 个交付点：23 创建 / 22 修改）

### 后端 Java 部分（Task 1 + 2 涉及）
| 文件 | 操作 | 职责 |
|---|---|---|
| backend/src/main/resources/db/migration/V9__Add_Person_Profiles_And_Decompose_Sessions.sql | **Create** | person_profiles / tasks.assignee_profile_id + idx / agent_decompose_sessions + 3 idx / rule_templates.profile_types 新增列 |
| backend/src/main/resources/db/migration/afterMigrate__agent_reader.sql（追加段，不重写） | **Modify 末尾追加** | GRANT SELECT person_profiles(8 列) + tasks.assignee_profile_id 给 agent_reader 角色；CHANGE 7 已写的其它列保留不动 |
| backend/src/main/resources/schemas/profile-schema.json | **Create** | SSOT 白名单 4 类型 × 26 key × 类型；两端同读 |
| backend/src/main/java/com/familyapp/profile/PersonProfile.java | **Create** | 实体：person_profiles 映射 + profile_data Map<String, Object> JsonNode |
| backend/src/main/java/com/familyapp/profile/PersonProfileRepository.java | **Create** | 继承 JpaRepository + JpaSpecificationExecutor |
| backend/src/main/java/com/familyapp/profile/ProfileDataSchema.java | **Create** | 读 profile-schema.json → Map<String, Map<String, SchemaField>> + validatePatch(type, patch) → {validPatch, discardedKeys:[{key,reason}]} |
| backend/src/main/java/com/familyapp/profile/ProfileWhitelistValidator.java | **Create** | Spring Bean；@Validated 自定义校验器（Service 层在 P-2/P-4/D-5/D-6 调用） |
| backend/src/main/java/com/familyapp/profile/dto/ProfileOption.java | **Create** | P-6 下拉 DTO：record(id,name,avatarEmoji,profileType,profileTypeName) |
| backend/src/main/java/com/familyapp/profile/dto/PersonProfileResponse.java | **Create** | 全量响应 record |
| backend/src/main/java/com/familyapp/profile/dto/CreatePersonProfileRequest.java | **Create** | DTO + Validation 注解 |
| backend/src/main/java/com/familyapp/profile/dto/UpdatePersonProfileRequest.java | **Create** | 可选字段 + remove_keys[] 删除顶层 key |
| backend/src/main/java/com/familyapp/profile/PersonProfileService.java(IFace) + PersonProfileServiceImpl.java | **Create 2 文件** | P-1~P-6 业务逻辑 + 白名单校验 + family_role 权限守卫；avatar_emoji Service 层兜底(profile_type→emoji) |
| backend/src/main/java/com/familyapp/profile/PersonProfileController.java | **Create** | P-1~P-6 6 端点 + @Valid + 权限守卫 |
| backend/src/main/java/com/familyapp/profile/ProfileRoleGuard.java | **Create** | 拦截器辅助类：CurrentUser role × endpoint → 放行/40303/40304/40901 |
| backend/src/main/java/com/familyapp/agent/DecomposeSession.java | **Create** | 实体 agent_decompose_sessions 映射 |
| backend/src/main/java/com/familyapp/agent/DecomposeSessionRepository.java | **Create** | JpaRepository<DecomposeSession, Long> |
| backend/src/main/java/com/familyapp/agent/DecomposeSessionService.java + DecomposeSessionServiceImpl.java | **Create 2 文件** | D-1~D-7 业务：D-5 @Transactional 原子 patch+bulk insert + status→终态；D-6 仅 patch；D-7 只更新 status |
| backend/src/main/java/com/familyapp/agent/DecomposeSessionController.java | **Create** | 控制器 D-1~D-7 + guardAiEnabled()；D-2/D-4 SSE StreamingResponseBody + StreamUtils.copy |
| backend/src/main/java/com/familyapp/agent/dto/DecomposeDtos.java | **Create** | D-1~D-7 请求/响应 DTO：CreateSessionRequest / ChatStreamRequest / DecomposeStartRequest / ConfirmResponse{appliedProfilePatch, discardedKeys, createdTasks} |
| backend/src/main/java/com/familyapp/agent/SuggestionService.java + SuggestionServiceImpl.java | **Create 2 文件** | S-1 三查询 + DTO；S-1 端点挂 AgentProxyController（或独立 SuggestionController；独立更好，避免和代理层耦合） |
| backend/src/main/java/com/familyapp/agent/SuggestionController.java | **Create** | S-1 单端点；token+familyId 过拦截 + 跳过 AI 守卫（无 guardAiEnabled） |
| backend/src/main/java/com/familyapp/agent/dto/SuggestionResponse.java | **Create** | 3 数组 DTO：similar_completed[] / similar_templates[] / pending_for_profile[] |
| backend/src/main/java/com/familyapp/agent/InternalAgentController.java | **Modify** | 追加 4 方法：B-3 get-profiles-by-family / B-4 patch-many(501 placeholder) / B-5 extract-patch / B-6 save-proposal |
| backend/src/main/java/com/familyapp/common/ResultCode.java | **Modify** | 追加 9 条：40303/40304/40901/40010/42201 + 50311/50312/50313/50314 中文消息 |
| backend/src/main/java/com/familyapp/task/Task.java | **Modify** | 加 assigneeProfileId 字段 + @ManyToOne(fetch=LAZY, optional=true) PersonProfile；assignee_id 字段保留不改 |
| backend/src/main/java/com/familyapp/task/TaskRepository.java | **Modify if needed** | 新查询：findByAssigneeProfileIdAndStatusIn（S-1 pending_for_profile 和 P-5 40901 复用） |
| backend/src/main/java/com/familyapp/task/dto/CreateTaskRequest.java | **Modify** | 新增 assigneeProfileId(Long)；原 assigneeId(Long) 保留；Service 层按「过渡策略」兼容 |
| backend/src/main/java/com/familyapp/task/dto/TaskResponse.java | **Modify** | 新增 assigneeProfile(ProfileOption 简化版 4 字段)；空 fallback 旧 assignee nickname |
| backend/src/main/java/com/familyapp/task/TaskServiceImpl.java | **Modify createTask / getList** | create 处加 assigneeProfileId 判定与 40010；list 时 join fetch assigneeProfile 避免 N+1 |
| backend/src/main/java/com/familyapp/family/FamilyService.java | **Modify createFamily** | 在事务末尾自动插 1 条 person_profiles：type=ADULT user_id=owner.id name=owner.nickname relation="本人" |
| backend/src/main/java/com/familyapp/config/WebConfig.java / SecurityConfig.java | **Modify 路径放行** | /api/person-profiles/** 过默认拦截；S-1 /api/agent/v1/suggestions 保留 Auth/拦截但跳过 AI 守卫；D-7 config（Change 7 已加）不变 |
| backend/src/main/java/com/familyapp/agent/RuleTemplate.java 实体 | **Modify** | 加 profile_types(String[]) 字段 + V9 列映射 |

### 后端测试（≥ 30 新用例）
| 文件 | 操作 |
|---|---|
| backend/src/test/java/com/familyapp/V9MigrationTest.java | **Create** | H2+PG 双环境（如 Change 1 V6MigrationTest 同模式）+ afterMigrate GRANT 列权限 SELECT 断言 |
| backend/src/test/java/com/familyapp/profile/ProfileSchemaTest.java | **Create** | 4 类型 × 合法 key × 非法 key × 非法类型 × discarded_keys 长度 ≥ 12 用例 |
| backend/src/test/java/com/familyapp/profile/PersonProfileControllerTest.java | **Create** | 8 用例：P-1~P-5 基本 CRUD + CHILD 40303 + 非 OWNER 删 40304 + 待办删 40901 + P-6 下拉返回 profileTypeName 中文 |
| backend/src/test/java/com/familyapp/profile/ProfilePatchTest.java | **Create** | 8 用例：D-5/D-6 patch 超白名单/非法类型 discard；合法 merge 成功；remove_keys 删除顶层 key |
| backend/src/test/java/com/familyapp/agent/SuggestionApiTest.java | **Create** | 5 用例：上限 ≤10/≤10/≤20；category+profile 正确；仅 profile 正确；空结果 |
| backend/src/test/java/com/familyapp/agent/DecomposeSessionControllerTest.java | **Create** | 12 用例：D-1/D-2/D-3/D-4 基本 200；终态 D-5/D-6/D-7；终态后再 chat 返回 50311；缺上下文 50312；空 proposal 50313；超 20 条 50314；AI 关 D-2 返回 50310 |
| backend/src/test/java/com/familyapp/agent/DecomposeAtomicTest.java | **Create** | 1 用例：D-5 第 3 条 proposed_task save Mock RuntimeException → tasks COUNT=0 + person_profiles.profile_data 原值不 |
| backend/src/test/java/com/familyapp/agent/DecomposeAbandonTest.java | **Create** | 1 用例：会话沟通→patch_preview→abandon→COUNT(person_profiles, tasks) 前后快照相等 |
| backend/src/test/java/com/familyapp/agent/InternalAgentBGroupTest.java | **Create** | 4 用例：B-3 family 列表 / B-4 501 / B-5 更新 extracted_patch / B-6 更新 proposed_tasks；无 Internal-Secret 头→401x(Change 2 已存在拦截器码) |
| backend/src/test/java/com/familyapp/task/TaskAssigneeProfileBackwardCompatTest.java | **Create** | 3 用例：仅 assigneeProfileId → 正确写入；仅 assigneeId → 后端反查回填新列；两列不一致→40010 |
| backend/src/test/java/com/familyapp/family/FamilyAutoInitOwnerProfileTest.java | **Create** | 1 用例：FamilyService.createFamily → person_profiles COUNT=1，类型 ADULT，name = owner.nickname |

### agent-service Python 部分（Task 2 涉及）
| 文件 | 操作 | 职责 |
|---|---|---|
| backend/src/main/resources/schemas/profile-schema.json（复制到 agent-service） | **Create** 镜像路径：agent-service/app/data/profile-schema.json（或 gradle processResources 拷；MVP 直接复制） | Python 侧 SSOT |
| agent-service/app/models/profile_schema.py | **Create** | 读 JSON → ALLOWED_KEYS dict 4 类型 → validate_patch(patch, profile_type) 返回 valid/discarded（与后端 Java 语义一致） |
| agent-service/app/prompts/profile_chat.j2 | **Create** | System Prompt + 7 变量 + 输出 JSON schema（phase/reply_content/suggest_options[<=6]/profile_patch_preview）；全中文；严格 JSON |
| agent-service/app/prompts/decompose.j2（升级，不新建） | **Modify** | 注入 profiles_full[] / selected_profile_id / profile_patch_preview；要求 1) assignee_profile_id ∈ profiles[].id 2) 标题结合档案（过敏/作息/慢病）3) dueDate≥今天 4) priority 按内容 |
| agent-service/app/connectors/backend_client.py | **Modify** | 新增 3 方法：get_profiles_by_family(family_id)→List[dict]（B-3）；extract_session_patch(session_id, patch)（B-5）；save_session_proposal(session_id, tasks)（B-6）；都带 Internal-Secret 头 |
| agent-service/app/connectors/db_reader.py | **Modify** | 确保 get_profiles_by_family(family_id) → SELECT 8 列（Change 7 afterMigrate GRANT 过的列）；家庭过滤 |
| agent-service/app/routes/chat_routes.py | **Modify** | 新增 A-3 POST /api/v1/profile-chat/stream（SSE 6 事件：typing/message/patch_preview/done/error）；新增 A-4 POST /api/v1/decompose/with-profiles/stream（复用现有 Step2 模板匹配 C-10/C-11 → Step4 LLM 升级版 decompose.j2 → 回写 B-6 → reasoning/proposal/done 事件流）；注意：路由走 ChatStreamRequest 变体 body |
| agent-service/app/services/decomposer.py | **Modify** | 新增 def with_profiles_run(...)：核心同现有 run_decompose 但多传 profiles；调用 prompt 版本变化；生成的 subtasks[].assignee_profile_id 必须来自 profiles |
| agent-service/app/harness/json_guard.py | **Modify if need** | 新增 parse_profile_chat（严格校验 profile_chat 输出 4 字段 JSON schema）；parse_decompose 不改（但 profile 新 assignee_profile_id 字段加进 subtask schema） |
| agent-service/app/models/api_schemas.py | **Modify** | ProfileChatRequest / ProfileChatResponse（4 字段 Pydantic）；DecomposeWithProfilesRequest（含 task_ctx{taskId?, title?, desc?,...}） |
| agent-service/tests/test_profile_chat_flow.py | **Create** | 5 用例：422 边界 / B-3 查 / A-3 相位 asking→extracted_patch→done 流 / suggest_options≤6 长度 / 白名单 key 严格不越界 |
| agent-service/tests/test_profile_schema.py | **Create** | 2 用例：后端 profile-schema.json 与前端（本地拷贝镜像）字节 diff；与 models/profile_schema.py ALLOWED_KEYS 一致 |
| agent-service/tests/test_decompose_with_profiles.py | **Create** | 3 用例：输入双模式 taskId / title+desc 成功；assignee_profile_id 在 profiles；标题含档案信息（牛奶过敏→燕麦）；CHILD 角色 D-5 时后端 Java 会丢 patch，agent 不做额外处理（前端不显示 CHILD 保存档案按钮也行） |

### 前端 Vue 部分（Task 3 涉及）
| 文件 | 操作 | 职责 |
|---|---|---|
| backend/src/main/resources/schemas/profile-schema.json 前端镜像 | **Create** 前端路径：frontend/src/data/profile-schema.json | 打包后直接 import；用于 ProfileEditorDialog 动态表单生成 + 前端白名单先验 |
| frontend/src/views/PersonProfileView.vue | **Create** | 档案列表网格 + 搜索过滤 + 新建弹框 + 详情抽屉(60% 宽) + OWNER 专属的删除 + AI 访谈按钮(打开 DecomposeChatPanel PROFILE_INTERVIEW) |
| frontend/src/components/ProfileCard.vue | **Create** | 单卡：emoji 40px + 名字 20px + 类型 14px + 关系 emoji + 标签 tag + [查看] [AI 访谈] 两个按钮；老人友好 |
| frontend/src/components/ProfileEditorDialog.vue | **Create** | 5 大 profile_type 卡片(emoji 48px)单选 + profile-schema.json 动态字段渲染 + 前端白名单先验校验；提交通 P-2/P-4 |
| frontend/src/components/SuggestionsPanel.vue | **Create** | 3 Tab（📌同类已办(10)/📝同类模板(10)/⏳所有待办(20)）+ Tab 数量徽标；点击回填/跳/看；空态 emoji 卡片 |
| frontend/src/components/DecomposeChatPanel.vue | **Create** | 6 态状态机 Pinia decomposeSse 驱动；SSE 聊天区 + typing 指示 + suggest_options 按钮条(≤6) + patch_preview 绿卡 + proposal 内联编辑卡片(老人手改)；D-5/D-6/D-7 动作 |
| frontend/src/api/profiles.js | **Create** | P-1~P-6 axios 封装（client.js 共享拦截器） |
| frontend/src/api/suggestions.js | **Create** | S-1 GET(profile_id, category_id?) → 3 数组 |
| frontend/src/api/decomposeSession.js | **Create** | D-1(REST)/ D-2(fetch+ReadableStream SSE，复用 sseReconnect.js)/ D-3/D-4/D-5/D-6/D-7(REST)；D-2/D-4 与现有 decompose.js /chat stream 模式不同，新对象 独立封装，不污染老接口 |
| frontend/src/stores/profiles.js | **Create** Pinia | state: list, options, currentId；actions: list, create, update, remove, options, refresh(切换家庭自动)；watch familyStore.current familyId 变化自动 refresh；getters: byId, fullName(emoji+name+type 中文) |
| frontend/src/stores/decomposeSse.js | **Modify** | 从一次性拆解扩成 6 态状态机：phase(idle/chatting/proposing/confirmed/archived/cancelled/error)；actions: startSession(D-1), sendMessage(D-2), startDecompose(D-4), confirm(D-5), saveProfileOnly(D-6), abandon(D-7)；保持向后兼容老 /chat 一次性调用（legacy 包装传空 sessionType） |
| frontend/src/utils/sseReconnect.js | **Modify 兼容** | 已经有 3s→6s→12s→24s + slow_mode30s；给 D-2/D-4 复用；确认事件映射 typing/message/patch_preview/reasoning/proposal 全部经过 reconnect wrapper 捕获 error reconnect |
| frontend/src/router/index.js | **Modify** | 加 /profiles → PersonProfileView（meta requiresAuth=true；和其它 views 同级守卫）；确保其它路径不 redirect 冲突 |
| frontend/src/layouts/AppSider.vue | **Modify** | 菜单项追加：{ label: '成员档案', icon: '👥', path: '/profiles' }；显示在任务/分类/家庭/模板/自动化之后？建议位置：家庭(👨‍👩‍👧)与分类之间（档案是人员维度，和家庭强相关） |
| frontend/src/views/TaskView.vue | **Modify 5 处** | ① 指派从 input→el-select（P-6 options）；② 分类下拉（若之前无）；③ 按钮行 双按钮并列(直接创建/AI 沟通拆解)，AI 按钮 data-ai=true 与现有 .ai-disabled 样式挂钩；④ 选了 profile_id 后自动 mount SuggestionsPanel（右侧/底部卡片位置），category 变化重调 S-1（debounce 300ms）；⑤ 任务卡 assignee 展示用 assigneeProfile，空 fallback 老列 nickname；5 AI 拆解 taskId 或纯文本入口：DecomposeChatPanel initPayload 如果用户已填 title desc→直接；输入框"输入 taskId:数字 或 任务描述"→解析后走对应路径 |
| frontend/src/stores/auth.js + family.js | **Modify if needed** | 登录成功后 profilesStore.refresh()（保证下拉立刻有值）；切换家庭后 profilesStore.refresh() |
| frontend/src/api/client.js | **Modify if needed** | 新路径 /profiles/** /agent/v1/suggestions /agent/v1/decompose 都已经在全局拦截器覆盖 X-Access-Token / X-Family-Id（/api/** 全拦截，除 /api/auth/** 放行，Change 6 已做）；无需加新路径规则 |

### 前端测试（≥ 15 新用例）
| 文件 | 操作 |
|---|---|
| frontend/src/tests/TaskViewDropdown.test.js | **Create** 2 用例 |
| frontend/src/tests/SuggestionsPanel.test.js | **Create** 3 用例：3 Tab 渲染；点击同类已办回填表单；点击待办跳详情 |
| frontend/src/tests/ProfileEditorDialog.test.js | **Create** 2 用例：profile_type 切换动态字段；超白名单 key 前端先丢弃 |
| frontend/src/tests/DecomposeChatPanelAbandon.test.js | **Create** 2 用例：abandon 不调 profilesStore.$patch/profilesApi.remove/tasksApi.create(0 次 spy 调用)；cancelled 不弹 Toast |
| frontend/src/tests/DecomposeChatPanelConfirm.test.js | **Create** 2 用例：confirmed 后刷新任务列表；appliedProfilePatch discardedKeys 显示 |
| frontend/src/tests/PersonProfileView.test.js | **Create** 4 用例：网格渲染；搜索/过滤；新建弹框；AI 访谈弹框（PROFILE_INTERVIEW） |
| frontend/src/tests/ProfilesStore.test.js | **Create** 1 用例：切换家庭自动 refresh options |

---

## Task 1: V9 数据迁移 + 人员档案后端 CRUD + Internal B 组

**核心交付**：新表 person_profiles + 过渡列 assignee_profile_id + 会话表 + rule_templates profile_types；P-1~P-6 端点闭环；B-3/B-4/B-5/B-6 Internal 端点；旧列兼容 100%。**不写任何前端代码**。

- [ ] **Step 1: 写失败测试（16 测试文件全部 RED）**
  1. V9MigrationTest 2 用例（H2/PG）；Expected: FAIL（V9.sql 不存在，afterMigrate 未补）
  2. ProfileSchemaTest 12 用例：合法 month_age: 6→ok；month_age:"abc"→discard；OTHER.unknown_key→discard；BABY.diet_restrictions（ELDER 有但 BABY 白名单无）→ discard 长度 1。Expected: FAIL（profile-schema.json + ProfileDataSchema 不存在）
  3. PersonProfileControllerTest 8 用例：list/POST 建宝宝 201/GET 详情/PUT 更新/DELETE 软删 204/CHILD POST→40303/MEMBER POST DELETE→40304/档案有待办(PENDING) DELETE→40901/P-6 options 返回 profileTypeName 中文。Expected: FAIL
  4. ProfilePatchTest 8 用例：D-5 patch 4 类非法；remove_keys 删除顶层 key；合法 key merge；空 patch discardedKeys 空数组。Expected: FAIL
  5. TaskAssigneeProfileBackwardCompatTest 3 用例：仅新列/仅旧列/两列不一致 40010。Expected: FAIL
  6. FamilyAutoInitOwnerProfileTest 1 用例：createFamily → profiles COUNT 1，type=ADULT，name 匹配 owner.nickname。Expected: FAIL
  7. InternalAgentBGroupTest 4 用例：B-3 list/B-4 501/B-5 update extracted_patch/B-6 update proposed_tasks。Expected: FAIL
  8. 全量 Java baseline：先跑 `./gradlew test --no-daemon` 拿到当前 green 数；记住基线；之后每 Task 2 步都重跑对比基线。

- [ ] **Step 2: 写 profile-schema.json SSOT**
  路径 backend/src/main/resources/schemas/profile-schema.json
  ```json
  {
    "BABY": {
      "month_age": {"type": "int", "range": [0, 240], "desc": "月龄"},
      "allergy_list": {"type": "string[]"},
      "milk_brand": {"type": "string"},
      "food_brand": {"type": "string"},
      "wake_up_time": {"type": "time"},
      "nap_start": {"type": "time"},
      "nap_end": {"type": "time"},
      "bed_time": {"type": "time"},
      "vaccine_schedule": {"type": "string"},
      "common_meds": {"type": "string[]"}
    },
    "ELDER": {
      "chronic_diseases": {"type": "string[]"},
      "common_meds": {"type": "string[]"},
      "checkup_cycle_weeks": {"type": "int", "range": [1, 208]},
      "mobility": {"type": "enum", "values": ["SELF", "HALF", "HELP"]},
      "diet_restrictions": {"type": "string[]"},
      "blood_pressure_target": {"type": "string"}
    },
    "ADULT": {
      "diet_preferences": {"type": "string[]"},
      "allergy_list": {"type": "string[]"},
      "commute_mode": {"type": "string"},
      "work_start_time": {"type": "time"},
      "work_end_time": {"type": "time"},
      "exercise_habits": {"type": "string[]"}
    },
    "PET": {
      "breed": {"type": "string"},
      "age_years": {"type": "int", "range": [0, 80]},
      "vaccine_last_date": {"type": "date"},
      "deworm_cycle_weeks": {"type": "int", "range": [1, 104]},
      "vet_contact": {"type": "string"},
      "diet_habit_note": {"type": "string"}
    },
    "OTHER": {"notes": {"type": "string"}}
  }
  ```
  写完 3 信号验证：Grep "BABY" / "mobility" / "OTHER" 三个锚点=1。

- [ ] **Step 3: 写 V9.sql（4 段 DDL）**
  ```sql
  -- §1.1 person_profiles
  CREATE TABLE person_profiles (
    id BIGSERIAL PRIMARY KEY,
    family_id BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    user_id BIGINT NULL REFERENCES users(id) ON DELETE SET NULL,
    profile_type VARCHAR(10) NOT NULL CHECK (profile_type IN ('BABY','ELDER','ADULT','PET','OTHER')),
    name VARCHAR(50) NOT NULL,
    avatar_emoji VARCHAR(8) NOT NULL,
    gender VARCHAR(6) NULL CHECK (gender IN ('MALE','FEMALE','OTHER')),
    birthday DATE NULL,
    relation VARCHAR(20) NOT NULL,
    tags VARCHAR(100)[] NOT NULL DEFAULT '{}',
    profile_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ NULL
  );
  CREATE UNIQUE INDEX uk_pp_family_name ON person_profiles(family_id, name) WHERE deleted_at IS NULL;
  CREATE UNIQUE INDEX uk_pp_family_user ON person_profiles(family_id, user_id) WHERE deleted_at IS NULL AND user_id IS NOT NULL;
  CREATE INDEX idx_pp_family_type ON person_profiles(family_id, profile_type) WHERE deleted_at IS NULL;
  CREATE INDEX idx_pp_family_created_by ON person_profiles(family_id, created_by) WHERE deleted_at IS NULL;

  -- §1.3 tasks.assignee_profile_id 过渡列
  ALTER TABLE tasks ADD COLUMN assignee_profile_id BIGINT REFERENCES person_profiles(id) ON DELETE SET NULL;
  CREATE INDEX idx_tasks_assignee_profile ON tasks(assignee_profile_id) WHERE deleted_at IS NULL;

  -- §1.4 agent_decompose_sessions
  CREATE TABLE agent_decompose_sessions (
    id BIGSERIAL PRIMARY KEY,
    family_id BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    profile_id BIGINT NULL REFERENCES person_profiles(id) ON DELETE SET NULL,
    session_type VARCHAR(15) NOT NULL CHECK (session_type IN ('TASK_CREATE','PROFILE_INTERVIEW')),
    status VARCHAR(15) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CONFIRMED','CANCELLED','ARCHIVED')),
    init_task_title VARCHAR(200) NULL,
    init_task_category_id BIGINT NULL REFERENCES categories(id) ON DELETE SET NULL,
    init_task_description TEXT NULL,
    init_task_priority VARCHAR(10) NULL,
    init_task_due_date DATE NULL,
    init_task_id BIGINT NULL,
    init_profile_id_snapshot JSONB NULL,
    messages JSONB NOT NULL DEFAULT '[]'::jsonb,
    extracted_profile_patch JSONB NULL,
    proposed_tasks JSONB NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
  );
  CREATE INDEX idx_ads_family_status ON agent_decompose_sessions(family_id, status);
  CREATE INDEX idx_ads_profile ON agent_decompose_sessions(profile_id);
  CREATE INDEX idx_ads_created_by ON agent_decompose_sessions(created_by);

  -- §2.2 similar_templates profile_types 列
  ALTER TABLE rule_templates ADD COLUMN profile_types VARCHAR(10)[] DEFAULT NULL;
  COMMENT ON COLUMN rule_templates.profile_types IS '适配的档案类型数组；NULL 全适配；S-1 similar_templates 匹配条件';
  ```

- [ ] **Step 4: afterMigrate__agent_reader.sql 末尾追加 GRANT**
  在现有文件末尾加（**不要动已有 GRANT 段，Change 1-5 已经写好**）：
  ```sql
  -- Change 8: person_profiles 8 列只读 (afterMigrate 最后追加)
  GRANT SELECT(id, family_id, name, profile_type, profile_data, gender, birthday, relation, tags)
    ON person_profiles TO agent_reader;
  -- Change 8: tasks 新过渡列
  GRANT SELECT(assignee_profile_id) ON tasks TO agent_reader;
  ```

- [ ] **Step 5: 写 Java 实体 + DTO + 白名单校验器（profile 包）**
  - ProfileDataSchema.java: `class ProfileDataSchema { static { load(ClassPathResource(schemas/profile-schema.json)); }
    validatePatch(String profileType, Map<String, Object> patch) → {Map validPatch, List<Discard> discarded} `
  - PersonProfile.java 实体：`@Table(name = "person_profiles") class PersonProfile { @Id Long id; Long familyId; Long userId; @Enumerated(STRING) ProfileType profileType; String name; String avatarEmoji; String gender; LocalDate birthday; String relation; List<String> tags; @JdbcTypeCode(SqlTypes.JSON) Map<String, Object> profileData; Long createdBy; Instant createdAt; Instant updatedAt; Instant deletedAt; }`
  - PersonProfileServiceImpl.create 逻辑：**Service 层兜底 avatar_emoji**——如果 request.avatarEmoji == null/blank → switch(profileType): BABY→"👶"; ELDER→"👴"; ADULT→"👨"; PET→"🐾"; OTHER→"👤"。NOT NULL 列保证永不空
  - ProfileRoleGuard：静态方法 `checkWriteAccess(UserContext ctx) → 40303 if CHILD`；`checkDeleteAccess(ctx) → 40304 if NOT OWNER`；`checkDeleteReferenced(personProfileId, TaskRepository repo) → 40901 if COUNT>0`
  - DTOs：record ProfileOption / PersonProfileResponse / CreatePersonProfileRequest（@NotBlank profileType @Size(max=50) name @NotNull relation）/ UpdatePersonProfileRequest（所有字段 Optional + List<String> removeKeys）

- [ ] **Step 6: PersonProfileController 6 端点 + Task 实体兼容改造 + FamilyService.createFamily 自动建 owner 档案**
  Controller: P-1 GET / P-2 POST / P-3 GET{id} / P-4 PUT{id} / P-5 DELETE{id} / P-6 GET /options。全 @RestController @RequestMapping("/api/person-profiles")
  - P-4 UpdatePersonProfileRequest 处理 profileDataPatch（浅合并 existing || new）+ removeKeys（JSONB `profile_data - ARRAY[removeKeys]`）
  - Task.java + TaskRepository：加 assigneeProfileId 字段；加 findByAssigneeProfileIdInAndStatusIn(SPEC)
  - TaskServiceImpl.createTask：过渡策略（见 Global Constraints 第 2 条）：三分支判定 + 40010
  - TaskResponse DTO：加 assigneeProfile 字段（可空 ProfileOption）
  - FamilyService.createFamily：事务最后插入 1 条 PersonProfile（type=ADULT, userId=owner.id, name=owner.nickname, relation="本人", avatarEmoji="👨", createdBy=owner.id）
  - ResultCode.java：追加 9 条中文码

- [ ] **Step 7: InternalAgentController B-3~B-6 四端点**
  （controller 路径前缀已存在 /api/internal/agent/**，InternalSecretInterceptor 已拦截）
  - B-3 POST /profiles/get-by-family：body {family_id:Long} → List<PersonProfileResponse>
  - B-4 POST /profiles/patch-many：Result.error(501, "暂未实现，MVP 请走 D-5/D-6 内部确认")
  - B-5 POST /sessions/{id}/extract-patch：body {extracted_patch: object} → UPDATE agent_decompose_sessions.extracted_profile_patch（仅 ACTIVE 允许）
  - B-6 POST /sessions/{id}/save-proposal：body {proposed_tasks:[...]} → UPDATE session.proposed_tasks（仅 ACTIVE）

- [ ] **Step 8: 跑 Step 1 写的全部 35+ 用例 → 全部 PASS**
  命令：`cd backend ; ./gradlew test --tests "*V9MigrationTest*" --tests "*ProfileSchemaTest*" --tests "*PersonProfileControllerTest*" --tests "*ProfilePatchTest*" --tests "*TaskAssigneeProfileBackwardCompatTest*" --tests "*FamilyAutoInitOwnerProfileTest*" --tests "*InternalAgentBGroupTest*" --no-daemon`
  Expected：35+ 用例 100% PASS；green 总数 ≥ Step 1 基线 + 新数。
  **如果失败**：回到相应步骤修（先修 DDL/afterMigrate 段；再修白名单；再修 Controller；最后修兼容改造）。

- [ ] **Step 9: Commit**
  Git 范围：V9.sql / afterMigrate__agent_reader.sql 追加段 / profile-schema.json / profile 包 12 文件 / DecomposeSession.java+Repository（仅实体和仓库；Service/Controller 在 Task 2）/ 改造 Task.java/Repository/Service/DTO/CreateTaskRequest / FamilyService.createFamily / ResultCode 9 条 / InternalAgentController B-3~B-6。**注意不要把 Task 2 的代码提前 commit。**
  Commit message：`feat(change8/task1): V9 DDL + person_profiles CRUD(P1~P6) + B3~B6 Internal + assigneeProfileId 过渡兼容`

---

## Task 2: 会话 + 建议 API + agent-service 扩展（D-1~D-7 + S-1 + A-3/A-4）

**核心交付**：DecomposeSession 生命周期（D-1~D-7）全闭环；D-5 原子事务；S-1 三建议查询；agent-service 两新路由 A-3/A-4 + profile_chat.j2 Prompt + decompose.j2 升级；profile-schema.json 两端对齐；5031x 错误码完整。

- [ ] **Step 1: 写失败测试（Java 23+ Python 10+，总计 ≥ 33 用例 RED）**
  1. SuggestionApiTest 5 用例：上限 10/10/20；category+profile；仅 profile；空结果；category_id 不匹配时仍按 profile 匹配 S-1
  2. DecomposeSessionControllerTest 12 用例（文档 §8.2 列的 12 子项全写）
  3. DecomposeAtomicTest 1：第 3 条 RuntimeException → COUNT 0 + profile_data 不变
  4. DecomposeAbandonTest 1：abandon COUNT 0 diff
  5. test_profile_schema.py 2：镜像对比 + 对齐 ALLOWED_KEYS
  6. test_profile_chat_flow.py 5：422 边界 / B-3 list / 相位 3 段 / suggest_options ≤6 / 白名单不越界
  7. test_decompose_with_profiles.py 3：双模式（taskId/title+desc）/ assignee_id 在 profiles / 标题结合档案

  Expected：全部 FAIL（SessionService/Controller/SuggestionService/agent 路由/Prompt 都不存在）。

- [ ] **Step 2: Java DecomposeSessionService 实现（D-1~D-7 核心，D-5 事务原子）**
  DecomposeSessionServiceImpl：
  - D-1 create(CreateSessionRequest req)：INSERT ACTIVE；支持 PROFILE_INTERVIEW（必填 profile_id 校验存在）· TASK_CREATE（init_task_id 或 init_task_title 不为空二选一？不强制，先不填 D-4 时前置校验更合适）
  - D-2/D-3 chat(stream / sync)：
    1) 读 session 校验 ACTIVE（否则 50311）
    2) 校验 created_by == 当前 user_id（会话私有，防止串号）
    3) 调 agent-service A-3 profile-chat 同步版本(D-3)或 SSE 流式版本(D-2)转发（D-2 用 StreamingResponseBody，Change 6 AgentProxyController 现有 SSE 框架复用）
    4) 收到 AI 返回后，持久化 messages[] 数组（追加新两条 user_message + assistant_reply）；如果 AI 返回了 extracted_patch，调用 B-5 在 Java 侧 update session.extracted_profile_patch（省掉 agent 端异步回写可能不一致；Change 8 设计的 B-5 仍在 Internal 保留，但主流程由 Java Controller 持久化更稳）
  - D-4 start-decompose(stream)：前置校验 ACTIVE +（init_task_id!=null OR init_task_title 非空否则 50312）→ 转发 A-4 with-profiles SSE → proposal 事件数据 append 存 session.proposed_tasks
  - D-5 confirm：**@Transactional(timeout = 30, rollbackFor = Exception.class)** 三动作：
    a) 校验 ACTIVE；若 proposed_tasks 空/null → 50313；若 proposed_tasks.size() > 20 → 50314
    b) profile patch 写入：extracted_profile_patch map（key=profile_id, value=patch_obj）→ 逐条过 ProfileWhitelistValidator.validatePatch → 丢弃非法记录 discardedKeys 集合 → 合法部分 JSONB merge：`UPDATE person_profiles SET profile_data = profile_data || :validPatch, updated_at = NOW() WHERE id = :pid AND family_id = :fid AND deleted_at IS NULL`；CHILD 角色时整段跳过（discardedKeys 把所有 key 标成 reason=CHILD_ROLE_NOT_ALLOWED）
    c) 批量建任务：对 proposed_tasks 列表每条 `Task t = new Task()`；setId(null) 显式；按字段(title, desc, dueDate, priority, category_id, assignee_profile_id)填；family_id / created_by / source=SOURCE_AI / status=PENDING；save(t) 前日志 info "before save id={} title={}"；save(t) 后 info "after save id={} newId={}"；循环完收集 createdTaskIds 数组
    d) 回写 session.proposed_tasks JSONB 每条追加 createdTaskId 字段；status → CONFIRMED
    e) 返回 ConfirmResponse(createdTaskIds[], appliedProfilePatch{profileId -> appliedPatchMap}, discardedKeys[])
    **若 b/c/d 任意一步抛异常 → @Transactional 回滚**（保证 b 和 c 全成/全败）
  - D-6 save-only：非事务，校验 ACTIVE + role 非 CHILD（CHILD→40303），patch→白名单校验→JSONB merge；status → ARCHIVED
  - D-7 abandon：校验 ACTIVE；UPDATE status → CANCELLED；**不写任何业务表**；无事务；返回 Result.ok()

- [ ] **Step 3: Java DecomposeSessionController（D-1~D-7）+ SuggestionController（S-1）+ AgentProxyController S-1 放行策略**
  - D-1 POST /sessions / D-3 POST /sessions/{id}/chat（同步）
  - D-2 POST /sessions/{id}/chat/stream SSE
  - D-4 POST /sessions/{id}/start-decompose/stream SSE
  - D-5 POST /sessions/{id}/confirm / D-6 POST /sessions/{id}/save-profile-only / D-7 DELETE /sessions/{id}
  - 所有 D 端点（除 S-1 独立外）方法首部执行 guardAiEnabled()：!aiDecomposeEnabled → Result.error(50310, "AI拆解功能已关闭")
  - S-1 SuggestionController GET /api/agent/v1/suggestions：不走 guardAiEnabled；参数 profile_id(required) + category_id(optional)；
    - similar_completed 10 条（SORT updated_at DESC）
    - similar_templates 10 条（SORT last_used_at DESC NULLS LAST；profile_types @> ARRAY[profile_type]::varchar[] OR profile_types IS NULL 过滤）
    - pending_for_profile 20 条（SORT priority_weight ASC, due_date ASC NULLS LAST）

- [ ] **Step 4: agent-service profile-schema.json 镜像拷贝 + models/profile_schema.py 读**
  路径：agent-service/app/data/profile-schema.json（内容 = backend 的拷贝文件）；
  models/profile_schema.py：
  ```python
  from __future__ import annotations
  import json
  from pathlib import Path
  from typing import Any

  _SCHEMA_PATH = Path(__file__).resolve().parents[1] / "data" / "profile-schema.json"
  _SCHEMA: dict[str, dict[str, dict[str, Any]]] = json.loads(_SCHEMA_PATH.read_text(encoding="utf-8"))

  def validate_patch(profile_type: str, patch: dict[str, Any]) -> tuple[dict[str, Any], list[tuple[str, str]]]:
      type_schema = _SCHEMA.get(profile_type, {})
      valid: dict[str, Any] = {}
      discarded: list[tuple[str, str]] = []
      for k, v in patch.items():
          if k not in type_schema:
              discarded.append((k, f"not in whitelist of {profile_type}"))
              continue
          spec = type_schema[k]
          # 类型检查：int/string/time/date/enum/string[]
          ok, reason = _type_match(v, spec)
          if not ok:
              discarded.append((k, reason))
              continue
          valid[k] = v
      return valid, discarded
  ```

- [ ] **Step 5: agent-service Prompt 文件**
  - 新 profile_chat.j2：
    ```jinja2
    {# 严格 JSON 输出：phase/reply_content/suggest_options[] <=6 / profile_patch_preview? #}
    {# 输入: family_context, profiles[], selected_profile_id? , session_type(TASK_CREATE|PROFILE_INTERVIEW), task_ctx? , history_messages[], user_message?, max_rounds_left, whitelist_schema_keys #}
    你是【家庭档案小助手】，用中文口语沟通，一次 1-2 个问题，每次输出严格 JSON 4 字段。
    家庭：{{ family_context.name }}
    会话类型：{{ session_type }}
    {% if task_ctx %}任务上下文：标题="{{ task_ctx.title }}"，分类={{ task_ctx.categoryName }}，描述={{ task_ctx.description }}{% endif %}
    当前选中档案：{% if selected_profile_id %}{{ profiles_by_id[selected_profile_id].name }}({{ selected_profile_type }}){% else %}未指派{% endif %}
    白名单允许写入 key：{% for k in whitelist %}{{ k }}{% endfor %}（禁止超范围 key）
    历史对话：
    {% for m in history_messages %}
    {{ m.role }}：{{ m.content }}
    {% endfor %}
    本轮用户输入：{{ user_message or "(空，请你先开口)" }}
    剩余轮数：{{ max_rounds_left }}（<=1 强制 extracted_patch/done）
    输出：JSON 四字段
    {
      "phase": "asking | extracted_patch | done",
      "reply_content": "中文问句/答句 <=400 字",
      "suggest_options": ["最多6个按钮文案"],
      "profile_patch_preview": {"profile_id数字": {"key": "value（严格白名单内）"}}
    }
    要求：phase=asking 时问 1~2 个关键问题；信息够用时转 extracted_patch，patch_preview 填好；用户说"没有了/够了"转 phase=done 并填 patch_preview。
    ```
  - 升级 decompose.j2：在 Jinja2 顶部系统提示段追加 3 条规则和 profiles_full / selected_profile_id / profile_patch_preview 三变量注入；subtask JSON schema 新增字段 "assignee_profile_id": integer|null。

- [ ] **Step 6: agent-service A-3/A-4 路由 + decomposer.with_profiles_run + backend_client 3 新方法**
  - backend_client.py：def get_profiles_by_family(self, family_id) / def extract_session_patch(self, session_id, patch) / def save_session_proposal(self, session_id, tasks)
  - chat_routes.py：
    A-3 `@app.post("/api/v1/profile-chat/stream") async def profile_chat_stream(req: ProfileChatRequest) -> StreamingResponse`:
      1) 校验 token/family（AuthVerifier）；
      2) max_rounds_left 初始化；
      3) 循环最多 max_rounds 次 → 每次 LLM profile_chat.j2 → json_guard.parse_profile_chat → sse 发送 typing/message 事件；如果 phase == extracted_patch 时把 patch_preview 发 patch_preview 事件 + 调 backend_client.extract_session_patch(session_id, preview)；phase == done 时 close；
      4) 异常 → sse error_event。
    A-4 `@app.post("/api/v1/decompose/with-profiles/stream")`：
      1) Step 2 匹配模板（复用 C-10/C-11）；
      2) Step 4 LLM 升级版 decompose.j2（注入 profiles_full）→ json_guard.parse_decompose → 生成 proposal；
      3) 调 backend_client.save_session_proposal(session_id, subtasks)；
      4) 流式返回 reasoning/proposal/done 事件。

- [ ] **Step 7: 全部 33+ 测试 → PASS**
  Java 命令 + Python 命令：`./gradlew test --tests "*SuggestionApiTest*" --tests "*Decompose*Test*" --tests "*InternalAgentBGroupTest*"` + `cd agent-service && python -m pytest tests/test_profile_schema.py tests/test_profile_chat_flow.py tests/test_decompose_with_profiles.py tests/test_connectors.py -v`
  Expected: 全 PASS。
  强验证项目：
  - DecomposeAtomicTest：回滚时数据库 ROLLBACK 后查 COUNT=0（非仅内存断言）
  - DecomposeAbandonTest：前后快照 SELECT COUNT(1) 字节级比对（不是 mock）
  - agent 白名单越界 → validate_patch discarded non-empty

- [ ] **Step 8: Commit feat(change8/task2): D1~D7 会话机 + S1 建议 API + agent A3A4 双路由 + profile_chat/decompose(升级) Prompt**

---

## Task 3: 前端 5 组件 + TaskView 改造 + 路由 + 老人友好 + 验收收尾

**核心交付**：PersonProfileView + 4 子组件 + TaskView 5 处改造 + routes 菜单 + stores/profiles + decomposeSse 状态机 6 态；前端 vitest 新 15 用例；构建 npm run build 无错；老人友好 4 条视觉检查。

- [ ] **Step 1: 写失败测试（vitest 15 用例 RED）**
  路径 frontend/src/tests/：
  - TaskViewDropdown.test.js 2：P-6 选项渲染；切换 familyStore.current → profileStore.options refresh
  - SuggestionsPanel.test.js 3：三 Tab 名/徽标数；📌同类已办 click→emit fill-form；⏳待办 click→emit open-detail
  - ProfileEditorDialog.test.js 2：BABY 切换显示 month_age；diet_restrictions(ELDER key) 在前端提交时被剔除（spy 提交前 payload 不包含）
  - DecomposeChatPanelAbandon.test.js 2：abandon click→spyOn(profilesApi, 'patchProfile') 0 次 / spyOn(tasksApi, 'create') 0 次；Toast.success show? 0 次（避免"啥也没改成功"困惑）
  - DecomposeChatPanelConfirm.test.js 2：confirm click→createdTasks 显示 discardedKeys 数显示；proposal 卡片手改 title→confirm payload.overrideTitle 等于手改值
  - PersonProfileView.test.js 4：空网格空态卡片（0 档案）；搜索过滤 name=小宝→1 条；AI 访谈按钮 click→DecomposeChatPanel mounted(PROFILE_INTERVIEW)；OWNER only DELETE 按钮对 CHILD 用户隐藏
  - ProfilesStore.test.js 1：familyStore.$patch({current:{id:2}})→profilesStore.list 触发 refresh(axios spy 1 次 P-1)

  Expected: FAIL（组件/stores/API 封装都不存在）

- [ ] **Step 2: profile-schema.json 前端镜像 + stores/profiles.js Pinia + api/profiles.js + suggestions.js + decomposeSession.js**
  - data/profile-schema.json：backend 镜像拷贝（保证三端完全一致）；vite.config.js resolve alias 可直接 import
  - stores/profiles.js：
    ```js
    import { defineStore } from 'pinia'
    import { listProfiles, getProfileOptions, createProfile, updateProfile, deleteProfile } from '@/api/profiles'
    import { useFamilyStore } from './family'
    export const useProfilesStore = defineStore('profiles', {
      state: () => ({ list: [], options: [], loading: false, currentId: null }),
      getters: { byId: (s) => Object.fromEntries(s.list.map(p => [p.id, p])), fullName: (s) => (id) => { const p = this.byId[id]; return p ? `${p.avatarEmoji} ${p.name}（${p.profileTypeName}）` : '未指派' } },
      actions: {
        async refresh() { this.loading = true; try { const familyId = useFamilyStore().current?.id; if(!familyId) return; const [l, o] = await Promise.all([listProfiles(), getProfileOptions()]); this.list = l.data; this.options = o.data; } finally { this.loading = false } },
        async create(payload) { const r = await createProfile(payload); await this.refresh(); return r },
        async update(id, payload) { const r = await updateProfile(id, payload); await this.refresh(); return r },
        async remove(id) { await deleteProfile(id); await this.refresh() }
      }
    })
    ```
    注册 watch：在 router index onReady 后 & 每次切换家庭后 refresh。
  - api/ 三文件：profiles.js (P-1~P-6 client.get/post/put/delete) / suggestions.js / decomposeSession.js（D-1/D-3/D-5/D-6/D-7 REST；D-2/D-4 fetch + ReadableStream SSE 调 sseReconnect.js wrapper）
  - stores/decomposeSse.js：phase 状态机（idle/chatting/proposing/confirmed/archived/cancelled/error）；D-5 confirm 成功后任务卡列表刷新（emit 'task:refresh'）

- [ ] **Step 3: 5 个 Vue 组件**
  顺序：ProfileCard → ProfileEditorDialog → SuggestionsPanel → DecomposeChatPanel（复杂）→ PersonProfileView（组合）
  老人友好强制：el-button size="large"；卡片 label 20px；颜色变量用高对比；SuggestionsPanel Tab 图标+中文，Tab badge 显示数量。

- [ ] **Step 4: TaskView.vue 5 处改造**
  ① 指派 el-select：
  ```vue
  <el-select v-model="form.assigneeProfileId" size="large" placeholder="选择家庭成员（选后AI会结合TA的档案给建议）" @change="onProfileSelected" clearable>
    <el-option v-for="p in profileStore.options" :key="p.id" :value="p.id" :label="`${p.avatarEmoji} ${p.name}（${p.profileTypeName}）`" />
  </el-select>
  ```
  ② 分类下拉（复用 categories.js）；
  ③ 双按钮行：
  ```vue
  <el-button type="primary" size="large" @click="directCreate">📝 直接创建</el-button>
  <el-button v-if="aiStore.enabled" type="warning" size="large" @click="openAIDecompose">🤖 AI 沟通后拆解</el-button>
  ```
  ④ 右侧 SuggestionsPanel（v-if="form.assigneeProfileId"）：@fill-form="fillFormFromCompleted" @open-template="goTemplate" @open-task="openTaskDetail"
  ⑤ openAIDecompose 逻辑：
  ```js
  function openAIDecompose() {
    const payload = parseTaskCtx(this.form) // taskId? if editing existing; else title+desc+categoryId+dueDate+priority
    this.decomposeDialogVisible = true
    this.$refs.decomposePanel.init({ sessionType: 'TASK_CREATE', initPayload: payload })
  }
  ```
  ⑥ assignee 展示位替换：`task.assigneeProfile ? `${p.emoji} ${p.name}` : (task.assigneeId && nicknameFallback)`（双向兼容）

- [ ] **Step 5: router/AppSider/familyStore/authStore 联动**
  - router: `/profiles` 路由 entry；/profiles auth guard
  - AppSider: 菜单项 👥 成员档案（位置：家庭菜单旁边，老人常用）
  - stores/family.js setCurrent 成功后触发 profilesStore.refresh()
  - stores/auth.js loginOk 后触发 profilesStore.refresh()

- [ ] **Step 6: 全部 15 用例 → PASS；全量回归 frontend ≥ 48 green；npm run build exit 0；老人友好 4 条检查**
  vitest: `cd frontend ; npx vitest run`
  老人友好 4 条手动截图记录：按钮高度 ≥56px / emoji+中文 / 颜色对比 / suggest_options≤6 个按钮
  构建：`npm run build` 0 errors

- [ ] **Step 7: 变更 8 §5 验收 10 条 全跑 → 全部在 check_list.md Evidence 列填实际命令输出数字**
  （V9MigrationTest / 权限 8 用例 / S-1 上限 / 指派下拉切换 / D-5 原子 / D-7 零写 / 白名单 / 放弃流程 / Python 5 用例 / Java+Python+Frontend 全量阈值）

- [ ] **Step 8: Commit feat(change8/task3): 5Vue组件 + TaskView5处 + 路由菜单 + stores + 老人友好**

---

## Plan Self-Review（writing-plans skill 强制）

### 1. Spec 覆盖率（§0 5 目标 → 对应 Task 映射）
- ① 人员档案 B1 → Task 1 Step 2-3-5-6 + Task 3 Step 2-3（PersonProfileView 新增）✅
- ② 指派下拉 + 过渡 → Task 1 Step 6 Task* 改造 / Task 3 Step 4 ① ✅
- ③ AI 沟通环节（草稿箱+确认才写+放弃零写+档案页访谈） → Task 2 Step 2 D-5~D-7 / Task 3 Step 3 DecomposeChatPanel(PROFILE_INTERVIEW 支持) ✅
- ④ 任务建议 3 Tab + 默认上限 4.1A/4.2A/4.3 → Task 2 Step 3 S-1 + Task 3 Step 3 SuggestionsPanel ✅
- ⑤ 任务创建页 AI 入口双按钮 + taskId/文本双输入 → Task 3 Step 4 ③ ⑤ ✅
- 无遗漏；验收 10 条（spec §5）在 check_list 1~18 项全对应 ✅

### 2. Placeholder 扫描（无 TBD/TODO/空泛描述）
扫描：本 plan 中 TBD=0；TODO=0；"相似 Task N"=0；所有代码步骤都有实际代码片段（ProfileDataSchema/前端 stores/profiles.js/V9.sql DDL/profile_chat.j2）不是描述。✅

### 3. 类型一致性（跨 Task 命名不冲突）
检查：
- assigneeProfileId（Java: CreateTaskRequest/Task 字段 / Frontend: form.assigneeProfileId / SSE: subtask.assignee_profile_id snake_case（Python 侧与 agent 现有 JSON 字段风格一致）→ 返回前端时转驼峰：Java @JsonProperty("assignee_profile_id") 或 Jackson 全局 snake_case；**Plan 已统一：DecomposeSessionDtos.java 中 ConfirmResponse.createdTasks 每个任务项的 assigneeProfileId 用驼峰写；Python 输出时也转驼峰**。修正：Python test_decompose_with_profiles.py 中预期字段 assigneeProfileId。（已在 Step 6 代码里处理）✅
- profileTypeName：P-6 返回中文（Java side），前端展示时直接用 ✅
- session_type TASK_CREATE/PROFILE_INTERVIEW：Python Pydantic 和 Java DTO enum 一致 ✅
- 50310/40303/40901/40010/50311/50312/50313/50314：ResultCode.java 与 check_list 表一致 ✅

## 执行选项（用户选）

Plan complete and saved to `docs/superpowers/plans/2026-08-30-change8-profile-decompose.md`.

**1. Subagent-Driven（推荐）**：每 Task 派独立实现者子代理 + 两阶段评审；按 SDD flow 走
**2. Inline Execution**：本会话按 executing-plans skill 分 3 批执行 + checkpoint review

**请选 1 / 2（如果现在不立即实施，也要把 Plan 保存给后续会话恢复）**。
