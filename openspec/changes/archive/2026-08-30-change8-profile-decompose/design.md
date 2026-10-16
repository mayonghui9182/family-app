# Design: Change 8 - 人员档案 + AI 对话式拆解

## D1: person_profiles 虚拟成员策略

**问题**：需求包含「宝宝档案」但宝宝通常没注册用户账号；如果档案只绑 users，宝宝挂不进去。

**方案**：person_profiles.user_id 允许 NULL；profile_type + name + relation 独立存在。Task assignee_profile_id 指向档案 ID，不再强依赖 user_id。UI 区分：档案卡片展示时，userId 非 NULL 的显示「账号」徽标，虚拟成员显示「未注册」。

**取舍**：BABY/ELDER/PET 大部分都 user_id=NULL 接受；老成员迁移(§1.5 afterMigrate)时用 NOT NULL。

## D2: 会话态草稿箱 vs 立即写业务数据（核心原子性）

**问题**：需求「放弃，不生成待办」同时「沟通的结果会更新人员档案」。如果每轮聊天都实时写档案，用户点「放弃」就要回滚一连串 UPDATE，数据库无法简单回滚（聊天期间可能有其它并发写入）。

**方案**：采用「草稿箱 - 原子确认」模式（§1.4 agent_decompose_sessions）：
- D-1~D-4 只写会话表（messages / extracted_profile_patch / proposed_tasks）
- D-5 confirm 单事务 = ① JSONB patch 写 person_profiles（白名单校验，丢弃非法 key 不阻断）② 逐条 new Task() 批量 save ③ 终态 CONFIRMED
- D-6 save-only 单操作 = 写档案 + 终态 ARCHIVED
- D-7 abandon 单操作 = 终态 CANCELLED，跳过所有业务写

**取舍**：用户沟通时的「patch_preview 绿卡片」只是内存+会话预览，不是真写；必须点「确认/只保存档案」才落库。好处是零回滚，坏处是「放弃 = 白聊 5 轮啥也没存」。D-6 给用户提供「中途退出但存档案」选项缓解。

## D3: AI 拆解按 taskId 或 任务描述文本 双输入

**问题**：Change 4 的 /decompose 只支持 taskId（已有任务实例）。本次需求新增「直接在创建页输入标题文本直接拆，先不建 DRAFT」。

**方案**：A-4 decompose/with-profiles/stream 入参 task_ctx 支持两种模式：
- 有 task_ctx.taskId → 先查 tasks 拉 title/desc/categoryId/priority 再拆
- 有 task_ctx.title/desc/categoryId(无 taskId) → 直接按文本拆，不需要先落 DRAFT

后端 D-4 启动拆解时的前置检查 = taskId 非空 或 title 非空。

**取舍**：避免了「想先拆个大任务看看又得先建一条 DRAFT 再放弃」的脏写路径；同时兼容旧 taskId 用法。

## D4: profile-schema.json Single Source of Truth（防 drift）

**问题**：4 类档案白名单(§1.2)如果在 Java/Python/前端 3 端各写一份，未来增删字段容易不一致。

**方案**：新建 backend/src/main/resources/schemas/profile-schema.json（版本控制的单一真相源），格式：
```json
{
  "BABY": {"month_age": "int", "allergy_list": "string[]", "...": "..."},
  "ELDER": {"chronic_diseases": "string[]", "...": "..."},
  "ADULT": {"...": "..."},
  "PET": {"...": "..."},
  "OTHER": {"notes": "string"}
}
```
- Java ProfileDataSchema.java：ClassPathResource 加载 JSON，构建 Map<String, Map<String, SchemaField>>
- Python agent-service：构建阶段通过 Dockerfile 或 gradle processResources 把同一份 JSON 拷进 app/data/profile-schema.json；models/profile_schema.py 只负责读
- 前端 ProfileEditorDialog.vue：打包时把同 JSON 作为模块 import

**取舍**：build 链稍微复杂，但杜绝「前端允许 month_age，后端白名单不允许」的错误。R-2 风险的主要缓解手段。

## D5: SSE 事件流（D-2 沟通 + D-4 拆解复用现有 C-31 框架）

**现有 C-31 SSE 模式**：StreamingResponseBody + StreamUtils.copy，事件 classification/reasoning/proposal/done/error。

**扩展**：D-2（沟通）新增事件 typing / patch_preview / message；D-4（拆解）直接复用现有 classification+reasoning+proposal。后端 AgentProxyController C-31 的 SSE 框架不改实现（避免写两套流式逻辑），新增 D-2/D-4 两个端点进同一个 DecomposeSessionController。

**取舍**：减少一套 SSE 基础设施；但 SSE 事件类型现在是「通用 5 + 沟通专用 3」集合，前端 stores/decomposeSse.js 需按 phase 决定忽略哪些事件。

## D6: 建议面板 3 Tab 数据来源（S-1）

**实现选型**：
- similar_completed：读 tasks 表（后端已有 TaskRepository）
- similar_templates：读 rule_templates（已有 RuleTemplateRepository）+ V9 profile_types 新列
- pending_for_profile：读 tasks 表
三者都走后端直接 SQL，不调 agent-service（S-1 跳过 AI 开关守卫，数据查询本身和 AI 无关，用户关 AI 时也能用建议）。

**取舍**：低延迟，用户选档案后几乎瞬间显示；如果未来想把“同类”从字面匹配升级为语义向量匹配，再换内部 API 调 agent 加一层。

## D7: PersonProfile 与 User 的「账号归属」解耦

**现状**：assignee_id 直接绑 users.id。

**Change 8 过渡**：用户既可能是注册用户（有档案 + 有 user_id）也可能是虚拟成员（有档案 + 无 user_id）。任务通知/推送/提醒未来如要发给「本人」，需再判断档案.user_id 是否 NULL（非 NULL 的发登录用户通知；NULL 的只显示在任务卡「指派给：小宝」，不推送）。本次 MVP 不做推送变更，仅保证 TaskResponse 展示空 user_id 时也能显示档案名+emoji。

**取舍**：先 UI 闭环，通知/推送留 Change 9 增强，不阻塞主线。

## D8: 权限模型：OWNER/ADULT/CHILD 对档案模块授权表

| 能力 | OWNER | ADULT | CHILD |
|---|---|---|---|
| P-1/P-3/P-6 读档案 | ✓ | ✓ | ✓ |
| P-2 新建档案 | ✓ | ✓ | ✗ 40303 |
| P-4 更新档案 | ✓ | ✓ | ✗ 40303 |
| P-5 删除档案 | ✓ (40901 if pending) | ✗ 40304 | ✗ 40304 |
| AI 沟通会话（D-1~D-4） | ✓ | ✓ | ✓ |
| D-6 只保存档案 | ✓ | ✓ | ✗ 40303（CHILD 不允许最终写档案） |
| D-5 确认生成任务 | ✓ | ✓ | ✓（生成任务可；但写档案部分仍然由后端拦截 CHILD 改成仅生成任务） |
| D-7 放弃 | ✓ | ✓ | ✓ |

**注**：CHILD 用户发起 D-5 confirm 时，后端会把 extracted_profile_patch 全部丢弃（记 discarded_keys 全字段），只保留 proposed_tasks 生成任务动作。保证孩子用户不能「通过 AI 沟通后门修改档案」。

## D9: rule_templates profile_types 迁移策略

- V9 加 profile_types 列，默认 NULL（全适配），不破坏已有模板语义。
- 新建模板时（CategoriesView/TemplatesView 现有创建入口），新增一个「适配成员类型」多选框：全部 / 👶 / 👴 / 👨 / 🐾；为空 = 全部 = NULL。
- 这样 S-1 similar_templates LIMIT 10 查询即使缺标注也能给出老模板（按 category 匹配 + profile_types NULL 兜底返回），用户不会感觉模板变少。
