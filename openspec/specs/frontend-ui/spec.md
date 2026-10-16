# frontend-ui Specification

## Purpose
Vue 3 + Vite + Element Plus 的老人友好前端基线：Vue Router 4 守卫（login 公开、/tasks/categories/families/invites 鉴权）、Pinia（auth/family/font 三 store，localStorage 持久化 token/家庭ID/字号偏好）、统一 axios 客户端（X-Access-Token + X-Family-Id 双 Header、中文异常提示）、WCAG 2.1 AA 无障碍（Skip Link / aria-label / focus-visible / sr-only / prefers-contrast-reduced-motion）、按钮与字号三档规范，支持大屏老人操作。
## Requirements
### Requirement: Frontend tech stack SHALL be Vue 3 + Vite + Element Plus with Vue Router 4 and Pinia
Vue Router 4 SHALL handle routing with public `/login` route and authenticated `/` layout containing `/tasks`, `/categories`, `/families`, `/families/:id/invites` sub-routes. Pinia SHALL manage global auth and family state with localStorage persistence for token and currentFamilyId. Only `vue-router@4` and `pinia` SHALL be added as new npm dependencies; no other new packages SHALL be introduced.

#### Scenario: User visits / and is not logged in
When the user navigates to `/` without a token in localStorage, the router beforeEach guard SHALL redirect to `/login`.

#### Scenario: User visits /login and is already logged in
When the user navigates to `/login` with a valid token in localStorage, the router beforeEach guard SHALL redirect to `/tasks`.

### Requirement: Shared axios client SHALL inject X-Access-Token and X-Family-Id headers
The axios instance (`api/client.js`) SHALL have `baseURL: '/api'` and `timeout: 15000`. The request interceptor SHALL inject `X-Access-Token` header (from localStorage `'accessToken'`) for all non-`/api/auth/**` requests; and SHALL inject `X-Family-Id` header (from localStorage `'currentFamilyId'`) for `/api/tasks/**` and `/api/categories/**` requests. The response interceptor SHALL unwrap `{code, msg, data}`, show `ElMessage.error(msg)` when `code !== 0`, clear the token and redirect to `/login` on HTTP 401, and show Chinese prompts for 403 ("您的权限不足，无法操作"), timeout ("请求超时，请检查网络后重试"), and network error ("网络不好，请稍后再试").

#### Scenario: Sending a request to /api/tasks with token and current family
When calling `tasksApi.list()` while localStorage contains both `accessToken=AAA...` and `currentFamilyId=1`, the outbound HTTP request SHALL include headers `X-Access-Token: AAA...` and `X-Family-Id: 1`.

#### Scenario: X-Family-Id is missing when calling categories API
When the user opens the CategoryView without having selected a family (localStorage has no `currentFamilyId`), the request interceptor SHALL display ElMessage "请先选择一个家庭后再操作" and SHALL redirect the browser to `/families`.

#### Scenario: Backend returns HTTP 401
When the backend returns HTTP 401 (token expired or invalid), the response interceptor SHALL remove the token from localStorage, display ElMessage "登录已过期，请重新登录", and redirect the browser to `/login`.

### Requirement: LoginView SHALL provide elderly-friendly login and register forms
LoginView SHALL be a centered el-card (max-width 480px) with two el-tabs: "登录" and "注册". The login form SHALL contain a large (size=large) identity input with placeholder "邮箱或手机号" and emoji prefix, a password input (show-password toggle) with placeholder "请输入密码", and a full-width large primary button "登录". The register form SHALL contain email, phone, nickname, password, familyName inputs with Chinese placeholders and a full-width large button "注册并创建家庭". Form validation SHALL use Chinese messages. On successful login or register, the view SHALL write token/userInfo/families to authStore, set the first family (if any) as current via familyStore, and route to `/tasks`.

#### Scenario: User registers with nickname "王大爷", password "12345678", no familyName
When the user submits register with `{nickname: "王大爷", password: "12345678", email: "wang@test.com", phone: "", familyName: ""}`, the frontend SHALL POST to `/api/auth/register` with the same payload, receive the token, redirect to `/tasks`, and the created family SHALL be named "王大爷的家" by backend default.

#### Scenario: Login fails with wrong password for email
When the user submits login with an existing email but wrong password, the backend SHALL return `AUTH_EMAIL_WRONG_PASSWORD` (code 40113), and the client interceptor SHALL display the Chinese message "邮箱正确，但密码不对，再试试" directly via ElMessage without any extra handling in LoginView.

### Requirement: TaskView SHALL use 5-value DFA with card layout and flow transition buttons
TaskView SHALL display 5 task states with Chinese labels and semantic Element Plus tag types: DRAFT=草稿(info), PENDING=待办(warning), IN_PROGRESS=进行中(primary), COMPLETED=已完成(success), CANCELLED=已取消(danger). Tasks SHALL be rendered in el-card layout inside el-row/el-col. The transition matrix SHALL only render allowed buttons: DRAFT→{PENDING, CANCELLED}, PENDING→{IN_PROGRESS, COMPLETED, CANCELLED}, IN_PROGRESS→{COMPLETED, PENDING, CANCELLED}, COMPLETED/CANCELLED no buttons. A `canTransition(from, to)` function SHALL be used as both `v-if` render guard and click-time defensive check. Status changes SHALL call `tasksApi.updateStatus(id, status)`.

#### Scenario: User clicks "开始做" on a PENDING task
When the user clicks the "开始做" button on a PENDING task card, `canTransition('PENDING', 'IN_PROGRESS')` returns true, and TaskView SHALL call `tasksApi.updateStatus(taskId, 'IN_PROGRESS')`. Upon success the card tag SHALL change from "待办" (warning yellow) to "进行中" (primary blue) and buttons SHALL be refreshed to "已完成", "暂停", "取消".

#### Scenario: User tries to transition an invalid state via rapid click
When a race condition causes stale UI, clicking an already-COMPLETED card's (no-longer-rendered but cached) button results in `canTransition('COMPLETED', any)` returning false, and TaskView SHALL display ElMessage "任务状态不能这样切换" instead of calling the API.

### Requirement: FamilyView and InviteView SHALL support elderly-friendly family management and invitation workflow
FamilyView SHALL list the user's families with name, Chinese roleName (家庭管理员/成人成员/孩子成员), and member count. A "设为当前" action SHALL write the family to familyStore and localStorage for the X-Family-Id interceptor. The detail view SHALL show an editable family info card and a members table. OWNER role SHALL be able to: change another member's role between ADULT and CHILD, kick members (except OWNER), and transfer OWNER to another adult member. Non-OWNER members SHALL be able to leave the family (unless they are OWNER, with Chinese error). InviteView SHALL be mounted at `/families/:id/invites` with two zones: (1) accept zone with a large 6-char uppercase code input (auto upper-cased, non-letters filtered) and "加入家庭" button; (2) my-invites list showing code, maxUses, usedCount, expiresAt, status in Chinese (有效/已用完/已过期/已取消), plus a "取消" action. The create form SHALL accept maxUses (1-100 default 1) and ttlDays (1-30 default 7); after creating, the new code SHALL be highlighted with a copy-to-clipboard button and a Chinese tip "把邀请码告诉家人，他们输入后就能加入".

#### Scenario: OWNER transfers ownership to another adult member
When the OWNER clicks "转让管理员" on FamilyView, selects another adult member, and confirms, the frontend SHALL POST to `/api/families/{id}/transfer-owner` with the target id, then refresh the members list. The previous OWNER SHALL now show roleName "成人成员" and the target SHALL show "家庭管理员".

#### Scenario: User enters invite code "abcdef" (lowercase)
When the user pastes or types "abcdef" into the invite code input, the input SHALL auto-transform to "ABCDEF" uppercase, filter any non-letter characters silently, and submit `inviteApi.accept({code: "ABCDEF"})` when "加入家庭" is clicked.

### Requirement: CategoryView SHALL provide CRUD + merge with color picker
CategoryView SHALL have an inline create form: name (required) + el-color-picker + create button. A table SHALL list categories showing a color swatch block, name, and task count (if the backend returns it). An edit dialog SHALL allow name+color updates. The delete workflow SHALL be two-phase: (1) first call `categoriesApi.del(id)` and if successful, show success; (2) if the backend rejects (tasks exist), open a "合并并删除" dialog with a target category selector, then call `categoriesApi.del(id, mergeToId)`. A dedicated merge zone SHALL also allow selecting source and target categories and confirming the merge.

#### Scenario: User tries to delete a category that has tasks
When the user clicks delete on a category with 3 linked tasks, the backend returns an error (such as CATEGORY_IN_USE), the client interceptor shows the Chinese message, and CategoryView SHALL prompt a "合并并删除" dialog. After selecting target "家务" and confirming, the frontend SHALL call `categoriesApi.del(sourceId, mergeToId)` or `merge(sourceId, mergeToId)+del(sourceId)` and show success.

### Requirement: AppHeader family switcher and AppSider nav SHALL provide elderly-friendly global navigation
AppHeader SHALL display an el-select dropdown to switch the current family (label: "切换家庭："), the current user's nickname, and a "退出登录" button. Switching family SHALL update familyStore and localStorage then trigger a refresh of the current view's data. AppSider SHALL show three menu items with large emoji + Chinese text: 📋 我的任务, 🏷️ 分类管理, 👨‍👩‍👧 家庭管理 (routing to `/tasks`, `/categories`, `/families` respectively).

#### Scenario: User switches family from "王家" to "李家"
When the user chooses "李家" from AppHeader's family dropdown, the page SHALL not reload; instead familyStore.setCurrent('李家') SHALL update localStorage.currentFamilyId and the current view (e.g. TaskView) SHALL refetch data, causing the next API call to include `X-Family-Id: <李家中的id>`.

### Requirement: Elderly UX base rules SHALL apply across the application
All views SHALL use Element Plus `size="large"` inputs and buttons; task/family/invite states SHALL map to semantic colors (success/warning/primary/danger/info) with Chinese labels and NO English abbreviations; all errors and validation messages SHALL be Chinese; icons SHALL use emoji characters (📋🏷️👨‍👩‍👧👤🔒✅✉️📱🏠) instead of icon components, so no `@element-plus/icons-vue` dependency SHALL be added.

#### Scenario: Build check for forbidden dependencies
When the frontend is built, the `package.json` dependencies section SHALL only contain `vue`, `vite`, `@vitejs/plugin-vue`, `axios`, `element-plus`, `vue-router`, `pinia` and related build tools. It SHALL NOT contain `@element-plus/icons-vue`, `dayjs`, `lodash`, or any packages not in the original list except `vue-router` and `pinia`.

