# Delta Spec: profile-decompose (Change 8)

> 本文件描述相对于 openspec/specs/ 现有 4 份主规范(category-mgmt / daily-task-mgmt / family-mgmt / frontend-ui)的增量变更。

## 1. 对 daily-task-mgmt spec 的增量

### 1.1 Task 实体
- 现有字段：保持 status 5 值(DRAFT/PENDING/IN_PROGRESS/COMPLETED/CANCELLED)、family_id/created_by/categoryId/priority/source/expiresAt 不变
- 新增列 assignee_profile_id BIGINT FK person_profiles(id) ON DELETE SET NULL；旧列 assignee_id(原 user_id)保留到 Change 10
- CreateTaskRequest 同时接受 assigneeProfileId / assigneeId；前者优先；两列不一致返回 40010
- TaskResponse 新增 assigneeProfile{id,name,emoji,profileTypeName} 展示字段；空 fallback 旧列反查 nickname

### 1.2 新 API
- /api/person-profiles/** (P-1~P-6) 家庭人员档案 CRUD + 下拉
- /api/agent/v1/suggestions (S-1) 3 Tab 建议
- /api/agent/v1/decompose/sessions/** (D-1~D-7) 沟通+拆解会话

### 1.3 Rule Templates 列扩展
- rule_templates 追加 profile_types VARCHAR(10)[] 列，模板可标注适配哪种档案类型；NULL 表示全类型适配
- S-1 similar_templates 查询：profile_types @> ARRAY[:profile_type]::varchar[] OR profile_types IS NULL

## 2. 对 family-mgmt spec 的增量

### 2.1 家庭创建时初始化 owner 档案
- FamilyService.createFamily 事务末尾 → 自动写 person_profiles 1 条(type=ADULT, userId=owner, name=owner.nickname, relation=本人)
- 保证新家庭下拉指派永远至少有 1 人

### 2.2 角色权限扩展
- CHILD：对 P-2/P-4/P-5 一律 40303（只能读档案/看建议/发起 AI 拆解会话不能写档案）
- 删除档案(P-5) 仅 OWNER，其它返回 40304；如有 PENDING 任务 40901

## 3. 对 frontend-ui spec 的增量

### 3.1 路由 & 侧边栏
- 新增 /profiles → PersonProfileView（requiresAuth）
- AppSider 新增菜单项：「👥 成员档案」

### 3.2 老人友好约束(继承) + 新增
- 新增 DecomposeChatPanel：输入框 size=large + suggest_options 按钮条(<=6 项，老人点按不手打)
- AI 沟通过程中 patch_preview 用绿色高亮卡片显示，不做静默更新
- 取消流程不弹 Toast（以免用户困惑啥也没改怎么“成功”）

## 4. 对 category-mgmt spec 的增量
- 无破坏性变更；S-1 查询中 category_id 过滤复用 categories.id 现有 FK 即可
