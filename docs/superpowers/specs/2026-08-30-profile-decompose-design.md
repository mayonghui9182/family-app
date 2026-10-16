# 家庭人员档案 + AI 对话式拆解设计规格（Change 8）

> 日期：2026-08-30 · 版本：v1.0（用户 2026-08-30 批准 §1~§4 全通过）

## 0. 摘要

本次在 Change 1-7 已交付的「家庭成员任务 + AI 拆解 MVP」基础上，做 5 件事：

| # | 目标 | 对应章节 |
|---|------|---------|
| 1 | 建立统一家庭人员档案（已注册用户 + 未注册虚拟成员：宝宝/老人/宠物等） | §1 数据模型 |
| 2 | 任务创建指派下拉：从输入 user_id → 下拉选家庭成员档案 ID | §2 / §4 |
| 3 | AI 拆解沟通环节：任务创建页 / 档案页均可与 AI 多轮沟通；确认才写档案+生成任务，放弃啥都不写 | §2 D-1~D-7 / §3 / §4 |
| 4 | 基于档案的任务建议（3 Tab：同类已办≤10 / 同类模板≤10 / 该成员所有待办≤20） | §2 S-1 |
| 5 | 任务创建页 AI 入口：直接新增 / AI 拆解新增 双按钮并列，支持 taskId 或纯文本描述拆解输入 | §4 TaskView |

硬约束（继承 project_memory + 本次新约束）：
- 所有新表 100% family_id 多租户隔离 + 软删除 deleted_at
- person_profiles.profile_data 4 类 JSONB 有白名单，AI 写超范围 key 会被丢弃并记 warning
- 只有 D-5/D-6/D-7 三个端点会写业务表；D-1~D-4 只写会话表（放弃 = 零回滚成本零脏写）
- D-5 confirm 必须 @Transactional 原子：档案 patch + 批量建任务 同成同败
- UI 老人友好：大输入框(20px+)、大按钮(size=large)、emoji 图标、中文字面、建议项用按钮不手打
- 前端仍走 shared client（X-Access-Token + X-Family-Id）；/api/person-profiles/** 加拦截器链

---

## 1. 数据模型（V9 Flyway 迁移）

### 1.1 person_profiles 表

家庭「人员档案」统一表：已注册用户（user_id NOT NULL）+ 虚拟成员（user_id NULL）都用这张。

```sql
CREATE TABLE person_profiles (
  id BIGSERIAL PRIMARY KEY,
  family_id BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  user_id BIGINT NULL REFERENCES users(id) ON DELETE SET NULL,
  profile_type VARCHAR(10) NOT NULL
    CHECK (profile_type IN ('BABY','ELDER','ADULT','PET','OTHER')),
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
CREATE UNIQUE INDEX uk_pp_family_name
  ON person_profiles(family_id, name) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uk_pp_family_user
  ON person_profiles(family_id, user_id)
  WHERE deleted_at IS NULL AND user_id IS NOT NULL;
CREATE INDEX idx_pp_family_type
  ON person_profiles(family_id, profile_type) WHERE deleted_at IS NULL;
CREATE INDEX idx_pp_family_created_by
  ON person_profiles(family_id, created_by) WHERE deleted_at IS NULL;
```
（avatar_emoji 默认值在 Java 实体层处理 @Column(columnDefinition="VARCHAR(8) NOT NULL DEFAULT '\\xF0\\x9F\\x91\\xA4'::bytea::VARCHAR") 或直接由 Service 层在 create 时设置，避免 SQL/DDL 文件 emoji 字面量跨 9P/终端/客户端/数据库编码损坏；Service 层兜底：若 avatar_emoji 为空，按 profile_type 设置 BABY=👶、ELDER=👴、ADULT=👨、PET=🐾、OTHER=👤。同时给列加 NOT NULL 约束，使数据库也保证该字段永不空。）

### 1.2 profile_data JSONB 白名单（B1 精简版）

所有写入 profile_data 的 key 必须落在以下白名单内（P-4 PUT 手动改、D-5/D-6 AI 自动 patch 都过此校验）：

| profile_type | 允许 key | 值类型 | 说明 |
|---|---|---|---|
| BABY | month_age | int | 月龄 0~240 |
| BABY | allergy_list | string[] | 过敏清单 |
| BABY | milk_brand | string | 奶粉品牌 |
| BABY | food_brand | string | 辅食品牌 |
| BABY | wake_up_time | string HH:MM | 早上起床 |
| BABY | nap_start / nap_end | string HH:MM | 午睡起止 |
| BABY | bed_time | string HH:MM | 晚睡 |
| BABY | vaccine_schedule | string | 疫苗备注 |
| BABY | common_meds | string[] | 常用药 |
| ELDER | chronic_diseases | string[] | 慢病 |
| ELDER | common_meds | string[] | 常用药 |
| ELDER | checkup_cycle_weeks | int | 体检周期周 |
| ELDER | mobility | SELF/HALF/HELP | 自理能力 |
| ELDER | diet_restrictions | string[] | 饮食禁忌 |
| ELDER | blood_pressure_target | string | 血压目标 |
| ADULT | diet_preferences | string[] | 饮食偏好 |
| ADULT | allergy_list | string[] | 过敏 |
| ADULT | commute_mode | string | 通勤方式 |
| ADULT | work_start_time / work_end_time | string HH:MM | 工作时段 |
| ADULT | exercise_habits | string[] | 运动习惯 |
| PET | breed | string | 品种 |
| PET | age_years | int | 岁数 |
| PET | vaccine_last_date | string YYYY-MM-DD | 最近疫苗 |
| PET | deworm_cycle_weeks | int | 驱虫周期周 |
| PET | vet_contact | string | 兽医联系方式 |
| PET | diet_habit_note | string | 饮食习惯备注 |
| OTHER | notes | string | 自由文本 |

AI patch 校验策略（D-5/D-6 后端执行）：
1. 对每个 patch key，若不在该 profile_type 白名单 → 丢弃，记入 discarded_keys[] 响应字段 + WARN 日志（不阻断）
2. 值类型不匹配 → 同样丢弃，不阻断
3. JSONB merge 策略 = 顶层浅合并 existing || new_patch；删除 key 走 P-4 remove_keys[] 字段

### 1.3 tasks.assignee_id 过渡：新增 assignee_profile_id 列

```sql
ALTER TABLE tasks ADD COLUMN assignee_profile_id BIGINT
  REFERENCES person_profiles(id) ON DELETE SET NULL;
CREATE INDEX idx_tasks_assignee_profile
  ON tasks(assignee_profile_id) WHERE deleted_at IS NULL;
```

兼容策略：
- 过渡阶段（Change 8 全周期）：tasks 表保留 assignee_id（旧 user_id 列）和 assignee_profile_id（新档案列）共存
- 读：TaskResponse 优先返回新列 assigneeProfile，其次 fallback 到旧列 assigneeId 反查 nickname
- 写：CreateTaskRequest 同时接受两字段；传了新列优先用；只传旧列 → Service 层反查 person_profiles 回填新列；两列都传且不一致 → 40010「指派给谁参数冲突，请仅使用新档案字段」
- Change 10（未来）再删除旧列

### 1.4 agent_decompose_sessions 表（会话持久化）

沟通 + 拆解全生命周期的「草稿箱」：只有用户最终确认/放弃/只存档案时才写业务表。

```sql
CREATE TABLE agent_decompose_sessions (
  id BIGSERIAL PRIMARY KEY,
  family_id BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  profile_id BIGINT NULL
    REFERENCES person_profiles(id) ON DELETE SET NULL,
  session_type VARCHAR(15) NOT NULL
    CHECK (session_type IN ('TASK_CREATE', 'PROFILE_INTERVIEW')),
  status VARCHAR(15) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE','CONFIRMED','CANCELLED','ARCHIVED')),
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
CREATE INDEX idx_ads_family_status
  ON agent_decompose_sessions(family_id, status);
CREATE INDEX idx_ads_profile ON agent_decompose_sessions(profile_id);
CREATE INDEX idx_ads_created_by ON agent_decompose_sessions(created_by);
```

状态机（写业务表的写入点 = 仅 3 个终态转换）：

- ACTIVE --D-5 confirm--> CONFIRMED [写 profile patch + insert tasks]
- ACTIVE --D-6 save-only-> ARCHIVED  [写 profile patch only]
- ACTIVE --D-7 abandon--> CANCELLED  [啥都不写]

终态不可逆；终态会话禁止再追加消息。

### 1.5 家庭成员档案初始化

- 新家庭创建（FamilyService.createFamily）：自动创建 owner 档案 1 条（type=ADULT）
- 老家庭数据迁移：V9 afterMigrate 脚本遍历活跃 family_members，每人插 1 条档案，按 users.birthday 估算类型（<3 BABY；>=60 ELDER；否则 ADULT）

---

## 2. 后端 API（Java Spring Boot）

### 2.1 人员档案模块 /api/person-profiles

| # | 方法 | 路径 | 功能 | 请求 | 返回 | 权限 |
|---|---|---|---|---|---|---|
| P-1 | GET  | / | 列档案（type 可过滤） | query type | List<PersonProfileResponse> | 全家可读 |
| P-2 | POST | / | 新建 | CreatePersonProfileRequest | PersonProfileResponse | OWNER/ADULT；CHILD=40303 |
| P-3 | GET  | /{id} | 详情 | - | PersonProfileResponse | 家庭内可读 |
| P-4 | PUT  | /{id} | 更新（含 remove_keys[]） | UpdatePersonProfileRequest | PersonProfileResponse | OWNER/ADULT |
| P-5 | DELETE | /{id} | 软删除 | - | Result<Void> | OWNER；待办绑定=40901 |
| P-6 | GET  | /options | 下拉轻量列表 | - | List<ProfileOption> | 全家可读 |

主要 DTO：
- ProfileOption(id, name, avatarEmoji, profileType, profileTypeName 中文)
- PersonProfileResponse(完整字段 + profile_data Map)
- CreatePersonProfileRequest / UpdatePersonProfileRequest（含 profileData / remove_keys）
- 错误码新增：40303/40304/40901/40010/42201

### 2.2 建议 API（S-1） GET /api/agent/v1/suggestions

查询参数：profile_id（必填 Long）· category_id（选填 Long）。
返回 3 数组（一次返回）：
- similar_completed：category_id + profile 或仅 profile / COMPLETED / ORDER BY updated_at DESC LIMIT 10
- similar_templates：rule_templates 匹配 (profile_types @> [type] OR NULL) + category_id（可选）/ is_active=true / LIMIT 10；V9 给 rule_templates 追加 profile_types VARCHAR(10)[] 列
- pending_for_profile：status DRAFT/PENDING/IN_PROGRESS + assignee_profile_id / 按优先级+due_date排序 LIMIT 20

S-1 端点走 token + familyId 拦截，但跳过 AI 开关 50310 守卫（本地查询不关 AI）。

### 2.3 AI 拆解 + 沟通会话 D-1~D-7

控制器 DecomposeSessionController @RequestMapping(/api/agent/v1/decompose)，所有端点 guardAiEnabled。

| # | 方法 | 路径 | 行为 |
|---|---|---|---|
| D-1 | POST | /sessions | 创建会话 ACTIVE，返回 session_id |
| D-2 | POST | /sessions/{id}/chat/stream | SSE 多轮沟通：转发 agent-service A-3；AI 返回同步写 session.messages + extracted_profile_patch（只写会话表） |
| D-3 | POST | /sessions/{id}/chat | D-2 同步缓冲版 |
| D-4 | POST | /sessions/{id}/start-decompose/stream | 沟通完毕拆解 SSE：前置检查 ACTIVE + 任务上下文 OK；转发 agent-service A-4；收到 proposal 先落 session.proposed_tasks（会话表）再推前端 |
| D-5 | POST | /sessions/{id}/confirm | @Transactional 原子：白名单校验 JSONB 浅合并 patch + 逐条 new Task()（禁止带 id）批量 save + status CONFIRMED + 回写每条 createdTaskId。失败整体回滚 |
| D-6 | POST | /sessions/{id}/save-profile-only | 白名单校验 patch → ARCHIVED；不写任务 |
| D-7 | DELETE | /sessions/{id} | status CANCELLED；不写 profile 不写 tasks，干干净净 |

D-5 防「新增被替代只剩一条」：每条 Task 严格 new Task()，setId(null)，前后 info 日志打印 id 变化。
错误码：50311 会话已结束 / 50312 缺任务上下文 / 50313 proposal 为空。

### 2.4 Internal API（B 组 agent 回调）

挂载 InternalAgentController，继承 InternalSecret：
- B-3 POST /internal/agent/profiles/get-by-family：返回家庭全档案（含 profile_data）
- B-4 POST /internal/agent/profiles/patch-many：MVP 留占位（501 Not Implemented），真正写档案走 D-5/D-6
- B-5 POST /internal/agent/sessions/{id}/extract-patch：body extracted_patch → UPDATE 会话表
- B-6 POST /internal/agent/sessions/{id}/save-proposal：body proposed_tasks → UPDATE 会话表

---

## 3. agent-service（Python FastAPI）

### 3.1 原则
- 读：DbReader 只读角色；person_profiles 需要在 afterMigrate__agent_reader.sql V9 后补 GRANT SELECT(name, profile_type, profile_data, gender, birthday, relation, tags, id, family_id)（否则 42501，Change 5 D12 同款坑）
- 写：全走 backend B-3~B-6 Internal API（带 Internal-Secret 头），agent 永远不直接 INSERT person_profiles/tasks

### 3.2 BackendClient 扩展 3 方法
- get_profiles_by_family(family_id) -> list[ProfileFull] → POST B-3
- extract_session_patch(session_id, patch) → POST B-5
- save_session_proposal(session_id, tasks) → POST B-6

### 3.3 新 Prompt prompts/profile_chat.j2

严格输出 JSON（json_guard L1 + L2）。输入变量：family_context / profiles[] / selected_profile_id / session_type / task_ctx / history_messages / user_message / max_rounds_left（默认 10）/ whitelist_schema。

输出 JSON schema 4 字段：
- phase：asking / extracted_patch / done
- reply_content：string（<=400 字中文自然语言问句/答句）
- suggest_options：string[] 最多 6 条（老人按钮点按）
- profile_patch_preview：{profile_id_str: patch_obj} 或 null（phase=extracted_patch 时填充）

System 提示要点：中文、一次只问 1-2 个关键问题、任务上下文明确时直奔主题、每个问题附 suggest_options、够了就 extracted_patch，用户说「没有了」转 done、严格只抽白名单 key。

### 3.4 拆解 prompts/decompose.j2 升级
新增注入变量 profiles_full[] / selected_profile_id / profile_patch_preview。要求：
1) subtasks[].assignee_profile_id 从 profiles 列表选（或 null）
2) 标题结合档案信息（牛奶过敏选燕麦米粉、老人高血压低盐、宝宝作息 21:00 睡 20:15 洗澡）
3) dueDate >= 今天；如果有 init_task_due_date 按时间线分配子任务
4) priority 按内容分配

### 3.5 新路由
- A-3 POST /api/v1/profile-chat/stream（SSE：typing/message/patch_preview/done/error；phase=extracted_patch 时回写 B-5）
- A-4 POST /api/v1/decompose/with-profiles/stream（Step2 模板匹配复用 C-10/C-11 → Step4 升级版 decompose.j2 生成 proposal → B-6 落会话 → reasoning/proposal/done 流式返回）

### 3.6 profile-schema.json Single Source of Truth
新建 backend/src/main/resources/schemas/profile-schema.json（JSON 定义 4 类型白名单 key + 类型）。Java ProfileDataSchema.java 从 JSON 加载；agent-service 通过资源拷贝或环境目录读同一份。两端同步，避免 drift。

---

## 4. 前端（Vue3 + Element Plus）

### 4.1 新文件清单
- views/PersonProfileView.vue（新增：档案列表 + 详情）
- components/ProfileCard.vue（新增：列表卡片）
- components/ProfileEditorDialog.vue（新增：新建/编辑动态字段）
- components/DecomposeChatPanel.vue（新增：沟通+拆解复用组件 6 态）
- components/SuggestionsPanel.vue（新增：3 Tab 建议面板）
- api/profiles.js（新增 P-1~P-6）
- api/suggestions.js（新增 S-1）
- api/decomposeSession.js（新增 D-1~D-7）
- stores/profiles.js（新增：Pinia 缓存当前家庭 profiles）
- stores/decomposeSse.js（改造：一次性 → 会话型多轮）

路由新增 /profiles → PersonProfileView（requiresAuth）。

### 4.2 PersonProfileView.vue（档案页 + AI 访谈）
- 顶部：家庭成员档案 标题 + 新建档案按钮
- 工具栏：搜索（name/tags 模糊） + 类型过滤（宝宝/老人/成人/宠物/全部）
- 卡片网格 ProfileCard：emoji + 名字 + 类型 + 关系 + 年龄/月龄 + AI访谈 + 查看
- 详情抽屉右侧 60% 宽：基本信息 / 分档字段（size=large，老人友好）/ 编辑 / 删除（OWNER）/ AI 访谈按钮（打开 DecomposeChatPanel PROFILE_INTERVIEW）

### 4.3 ProfileEditorDialog.vue
- profile_type 5 大卡片单选（emoji 48px）
- 选完后动态字段（schema 来自 profile-schema.json）：时间用 el-time-picker、数组用 el-select multiple+allow-create、数值用 el-input-number、mobility 等枚举用单选按钮组
- 提交前前端白名单校验（两端双保险）

### 4.4 TaskView.vue 改造（5 处）
1 指派给谁：从输入变下拉（el-select，P-6 轻量格式 emoji+名字+类型）
2 分类下拉（复用 categories.js）：S-1 需要 category_id
3 按钮行：直接创建 / AI 沟通后拆解（data-ai=true 走 .ai-disabled）/ 重置
4 建议面板 SuggestionsPanel：选了 profile_id 后自动调 S-1；3 Tab 同类已办(点回填表单)/同类模板(点跳模板)/所有待办(点跳查看)；Tab 带数量徽标；默认上限
5 AI 拆解支持 taskId 或纯文本：如果已填 title/desc 就直接作 ctx；用户如果输入 taskId:XXX → tasksApi.get(id) 拉详情再拆

### 4.5 DecomposeChatPanel.vue（核心状态机 6 态）
Props：sessionType(TASK_CREATE/PROFILE_INTERVIEW) · initPayload。

状态机：idle → chatting → proposing → confirmed/archived/cancelled（proposing 可重拆）。

| phase | UI | 按钮 |
|---|---|---|
| idle | 创建会话 loading | 无（自动 D-1） |
| chatting | 聊天区 + typing 指示 + message 气泡 + suggest_options 按钮条 + patch_preview 绿卡片 + 输入框(size=large) | 继续问 / 只存档案 / 沟通完毕开始拆解(TASK_CREATE) / 放弃 |
| proposing | reasoning 推理 + proposal 清单卡片(每条可内联编辑) | 让 AI 重拆 / 确认生成待办 / 放弃 |
| confirmed | 绿色成功卡 + 任务条数 + 档案更新摘要 + 生成 ID 列表 | 关闭 |
| archived | 黄色卡「档案已更新，未生成任务」 | 关闭 |
| cancelled | 灰色卡「未做任何改动，已放弃」(不弹 Toast 打扰) | 关闭 |
| error | 红色卡 + 中文错误 | 重试/关闭 |

调用链路：onMounted D-1 → 用户消息 D-2 SSE(typing/message/patch_preview/done) → 拆解 D-4 SSE(reasoning/proposal/done) → 可手改 proposal → D-5 confirm（写回覆盖）或 D-6 save-only 或 D-7 abandon。

硬约束（写单测）：cancelled 不调 profiles.save/tasks.create；confirmed 在 D-5 返回前不刷新 store。

### 4.6 TaskResponse 新字段 assigneeProfile(id,name,emoji,profileTypeName)
任务卡原 assigneeId 展示位 → 新列优先；空 fallback 旧列反查 nickname；兼容过渡平滑。

---

## 5. 验收 10 条
1. V9 两表 + 新列 + 索引 H2/PG 双环境成功（V9MigrationTest）
2. 人员档案 CRUD 权限 8 用例通过（PersonProfileControllerTest）
3. S-1 3 数组上限符合（SuggestionApiTest）
4. 指派下拉 + 切换家庭自动刷新（vitest TaskViewDropdown.test.js）
5. D-5 事务原子：第 3 条 save 失败前 2 条回滚 + 档案不变（DecomposeAtomicTest）
6. D-7 abandon 零写入：COUNT 快照前后相等（DecomposeAbandonTest）
7. patch 白名单 4 类错误 key + 非法类型 全 discard（ProfilePatchTest）
8. 前端放弃流程：profilesStore/tasksStore 未触发保存动作（vitest DecomposeChatPanelAbandon.test.js）
9. Python agent A-3/A-4 422 边界 + B-3/B-5/B-6 调用成功（pytest test_profile_chat_flow.py）
10. 全量回归 Java>=189 agent>=92 frontend>=48 green

## 6. 非目标
不做 health 曲线(B2) / 不做头像图片上传 / 不做 dueDate 分粒度 / 不立即删旧 assignee_id 列 / 不做 CHILD 编辑档案权限 / 不做跨家庭复用 / 不做 AI 定时自动更新档案

## 7. 风险 + 回滚
R-1 老任务回填新列漏数据：afterMigrate 统计后空列 <1% 才算通过
R-2 白名单校验漏写 key：profile-schema.json SSOT + 两端同读 + 单测断言
R-3 D-5 事务 30 条超时死锁：proposed_tasks 上限 20 条 + 事务超时 30s + 错误消息明确
R-4 9P 写语法文件损坏：走「V8 utf8 hex → python3 wb」链路，写后 3 信号验证
R-5 agent_reader 列授权缺列：afterMigrate GRANT 段后补 + E2E 全量 SELECT 验证

回滚：Flyway V9 Undo 可一次删除两表+新列；代码 git revert；前端 TaskView.vue.legacy 备份；AI 失效时「直接创建」永远可用。

## 8. 跨 Change 依赖
Change 8 依赖 Change 1-6 全合并；Change 7 未合并也能写（本变更本地跑 H2/pytest/vitest）；E2E 和 Change 7 收尾一起验证。
执行顺序 3 Task：Task1 数据+后端档案CRUD+B组 → Task2 会话+建议+agent扩展 → Task3 前端5组件+联调验收。Total ≈ 4 天。

## 9. 锚点
A-人员档案-BABY-key-白名单 §1.2
A-下拉指派-P6-ProfileOption §2.1
A-会话机-D5-原子 §2.3
A-放弃-D7-零写 §2.3 + §4.5
A-建议-S1-3Tab-上限 §2.2
A-沟通-多轮-suggest_options §3.3
A-创建页-双按钮-并列 §4.4 第3点
A-拆解-taskId-或-文本 §4.4 第5点
A-档案页-AI访谈按钮 §4.2
A-9P-写入-SOP 交接军规
