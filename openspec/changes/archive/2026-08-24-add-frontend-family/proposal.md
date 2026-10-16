## Why

add-family-member 已落地完整后端 REST API（auth/family/invite 全家桶 + tasks/categories 的 family_id 权限体系），但前端现状有三大阻塞：

1. **无认证/授权闭环**：TaskView 直接用 `GET /api/tasks?status=TODO` 硬编码状态，没有登录流程，X-Access-Token 和 X-Family-Id 两个必需 header 从未发送，后端返回 401/400；
2. **任务状态契约不匹配**：expand-task-model 将 TaskStatus 从 2 值 (TODO/DONE) 扩展到 5 值 DFA (DRAFT/PENDING/IN_PROGRESS/COMPLETED/CANCELLED)，但 TaskView 仍渲染 TODO/DONE，导致后端返回的状态无法显示、合法流转按钮也缺失；
3. **无家庭维度视图**：无登录/注册页、无家庭列表与切换、无成员管理、无邀请流程，老人用户无法实际使用后端的多人协作能力。

本 change 为后端 API 补齐前端使用层：Vue Router 4 + Pinia + axios 统一拦截器；老人友好的登录/注册大页面；家庭切换 + 侧边导航布局；TaskView 重写 5 值 DFA 卡片布局 + 状态流转按钮；CategoryView CRUD + 合并；FamilyView 列表/详情/成员管理；InviteView 邀请码生成与接受。全程落实**老人易用性约束**：大字体、大输入框、emoji 图标（不引入 @element-plus/icons-vue 新依赖）、全中文标签、简洁交互。

## What Changes

- **技术栈新增**：`vue-router@4` + `pinia`（package.json dependencies 新增），保持 Vite + Element Plus + axios，零其他新依赖
- **基础设施**：
  - `api/client.js` 共享 axios 实例（baseURL `/api`、15s 超时）：请求拦截器从 localStorage 注入 `X-Access-Token`（/api/auth/** 除外）+ 对 /api/tasks**/categories** 从 localStorage 注入 `X-Family-Id`；响应拦截器解包 `{code,msg,data}`，code!==0 用 ElMessage.error(msg) 中文提示，401 清 token 跳 /login，403/超时/网络错误分别提示「您的权限不足，无法操作」/「请求超时，请检查网络后重试」/「网络不好，请稍后再试」
  - `stores/auth.js` Pinia：token（localStorage 'accessToken' 持久化）、userInfo（id/email/nickname）、families 列表；actions: setToken/setUserInfo/setFamilies/logout；getter isLoggedIn
  - `stores/family.js` Pinia：currentFamily(id/name/role/roleName)、members 列表；actions: setCurrent（同步写 localStorage 'currentFamilyId'）、loadCurrentFamilyId、clear；getter currentFamilyId
  - `router/index.js` Vue Router 4：`/login`(public meta) + `/`(requiresAuth, AppLayout 容器) 含 `/tasks` `/categories` `/families` `/families/:id/invites`；beforeEach 守卫：未登录跳 /login，已登录访问 /login 跳 /tasks
  - 入口改造：`main.js` 注入 createPinia + router；`App.vue` 改为 `<RouterView />`
  - 布局容器：`layouts/AppLayout.vue` el-container(header+aside+main)；`components/AppHeader.vue` 家庭切换下拉 + 用户昵称 + 登出；`components/AppSider.vue` 侧边导航（📋 我的任务 / 🏷️ 分类管理 / 👨‍👩‍👧 家庭管理）emoji 图标、大字体
- **API 层 5 模块**（统一 client，独立 axios 已被 tasks.js 移除）：
  - `api/auth.js`：register / login / logout / me
  - `api/family.js`：9 端点匹配后端契约 (create/list/getDetail/update/leave/transferOwner/changeRole/kick + [delete 501])
  - `api/invite.js`：4 端点 (create/list/cancel/accept)
  - `api/categories.js`：list/create/update/del(mergeToId 选填) + merge(id, mergeToId)
  - `api/tasks.js` 改造：list/create/get/update/del + 新增 updateStatus(id, status) → POST /tasks/{id}/status
- **登录视图 LoginView.vue**：el-card 居中 max-width 480px；el-tabs 切换「登录」/「注册」；登录表单：identity 大输入框 placeholder「邮箱或手机号」👤 prefix + 密码 show-password 🔒 + 大号「登录」按钮；注册表单：邮箱/手机号/昵称/密码/家庭名 5 字段 + 大号「注册并创建家庭」按钮；老人提示文案（「邮箱和手机号至少填一个」、「密码 8 到 64 位」）；成功后 authStore 写 token/用户/家庭并跳 /tasks
- **TaskView.vue 改造（5 值 DFA + 卡片式）**：状态 5 值中文映射：DRAFT=草稿(info 灰)、PENDING=待办(warning 黄)、IN_PROGRESS=进行中(primary 蓝)、COMPLETED=已完成(success 绿)、CANCELLED=已取消(danger 红)；el-row/el-col 卡片布局；每卡片：标题+状态标签+描述+截止日期+assignee+DFA 允许的操作按钮；创建表单：标题必填+描述+截止日期+优先级(MEDIUM 默认)+assignee 选填；canTransition(from, to) 函数双重防御（按钮显示 + 点击时再校验）；状态切换走 tasksApi.updateStatus
- **CategoryView.vue**：el-card 标题「分类管理」；inline 创建表单 name 必填 + el-color-picker 选色；el-table 列：色块 + name + taskCount(若后端返回) + 操作；编辑弹窗；删除流程：先调用 del(id)，失败再弹「合并并删除」对话框选 mergeToId；独立合并区：源/目标选择 + 确认
- **FamilyView.vue**：列表卡片或 el-table：name + roleName 中文 + 成员数 + 「设为当前」「详情」「创建家庭」按钮；详情内联切换（不走路由）：家庭信息编辑 + 成员 el-table（nickname + roleName + joinedAt + 操作）；OWNER 权限：改角色（ADULT↔CHILD）、踢出成员、转让管理员；非 OWNER：退出家庭按钮；「设为当前」写 familyStore.setCurrent，同步 localStorage，供 tasks/categories 的 X-Family-Id 拦截器使用
- **InviteView.vue**：路由 `/families/:id/invites` 用 route.params.id；顶部「加入家庭」区：邀请码 6 位大写大输入框（自动转大写，过滤非字母）+ 「加入」按钮；下方「我创建的邀请」el-table：code + maxUses/usedCount + expiresAt + status 中文映射(ACTIVE=有效/USED=已用完/EXPIRED=已过期/CANCELLED=已取消) + 取消操作；创建表单：maxUses 数字 1-100 默认 1 + ttlDays 数字 1-30 默认 7 + 创建按钮，创建后高亮 code 并提供复制按钮，老人提示「把邀请码告诉家人，他们输入后就能加入」

## Scope
包含：技术栈 2 新依赖 + 基础设施 + API 层 + 5 个视图 + 布局组件

不包含：
- 后端 API 改动（后端契约稳定，不做任何修改）
- 前端单元测试/端到端测试（另起 change）
- 老人易用性细节优化（字号/对比度/语音朗读，另起 change）
- Docker 冒烟测试（另起 change）
- X-Family-Id 后端拦截器的 full CurrentUser(familyId, role) 补齐（仅运行时跳 /families 兜底）
- deleteFamily 接口实现（保持 NOT_IMPLEMENTED 501）

## Capabilities

| 能力 | 输入 | 输出 | 失败兜底 |
|---|---|---|---|
| 登录 | 邮箱/手机号 + 密码 | 跳转 /tasks，导航可用 | ElMessage 中文提示（邮箱未注册 / 手机号未注册 / 邮箱密码错 / 手机号密码错） |
| 注册 | 昵称 + 密码 + 邮箱/手机号选填 | 自动创建家庭，跳转 /tasks | 表单校验中文提示，重复账号「这个邮箱或手机号已经注册过了，直接登录吧」 |
| 切换家庭 | Header 下拉选家庭 | familyStore + localStorage 更新，tasks/categories 下一次请求自动带 X-Family-Id | 切换时拉取家庭详情并刷新当前视图 |
| 任务状态流转 | 点击卡片按钮 | 状态更新 + 卡片颜色/标签刷新 | canTransition 双重防御，非法流转 ElMessage「任务状态不能这样切换」 |
| 分类合并 | 源 + 目标选择 | 源分类任务迁移 + 源软删除 | 目标不存在或同家庭校验失败中文提示 |
| 邀请家人 | maxUses/ttlDays | 6 位邀请码 + 复制按钮 | 10 次生成冲突后「服务器开小差了，无法生成邀请码，请稍后重试」 |
| 接受邀请 | 6 位邀请码 | 跳 /families 并刷新家庭列表 | 邀请码过期「这个邀请码已经超过 7 天过期了，请让家人重新生成」 |

## Non-Goals
- 不做移动端优先布局（桌面浏览器可用即可，后续可做响应式）
- 不引入任何新的 npm 包（除 vue-router 和 pinia）
- 不做老人优化的高级细节（语音、字号调节、高对比度主题）
- 不做 i18n（仅中文）