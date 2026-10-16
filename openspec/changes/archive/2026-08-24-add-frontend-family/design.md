## 架构总览

```
frontend/src/
├── api/
│   ├── client.js           # axios 实例 + 拦截器（token + X-Family-Id + 错误处理）
│   ├── auth.js             # auth 端点封装
│   ├── family.js           # family 9 端点封装
│   ├── invite.js           # invite 4 端点封装
│   ├── categories.js       # category CRUD + merge
│   └── tasks.js            # 改造：共享 client + updateStatus
├── stores/
│   ├── auth.js             # token/userInfo/families (Pinia, localStorage 持久化)
│   └── family.js           # currentFamily/members (Pinia, localStorage 持久化 id)
├── router/
│   └── index.js            # Vue Router 4, public /login, requiresAuth /
├── layouts/
│   └── AppLayout.vue       # el-container: Header + Sider + main
├── components/
│   ├── AppHeader.vue       # 家庭切换 + 用户昵称 + 登出
│   └── AppSider.vue        # 侧边导航（大字体 emoji）
├── views/
│   ├── LoginView.vue       # 登录/注册 tab 大页面
│   ├── TaskView.vue        # 5 值 DFA + 卡片式
│   ├── CategoryView.vue    # CRUD + 合并 + 色块
│   ├── FamilyView.vue      # 列表 + 详情 + 成员管理（内联切换）
│   └── InviteView.vue      # 生成 + 接受 + 取消
├── App.vue                 # <RouterView />
└── main.js                 # createApp().use(pinia).use(router).mount('#app')
```

## 关键设计决策

### D1. 老人友好 UI 规格
- **字号**：输入框 size="large"，按钮 size="large"；body 默认 ≥14px，标签 ≥16px
- **图标**：零新依赖原则，全用 emoji（📋 任务、🏷️ 分类、👨‍👩‍👧 家庭、👤 用户、🔒 密码、✅ 完成）
- **颜色**：高对比度，Element Plus 默认语义色（success 绿/warning 黄/primary 蓝/danger 红/info 灰）对应 5 个任务状态，老人一眼可辨
- **输入框前缀**：用 `el-input #prefix` slot 放大 emoji span，不用 Icon 组件，避免引入 @element-plus/icons-vue
- **错误提示**：直接显示后端返回的中文 msg（client.js 拦截器解包），不含技术词汇，不含英文缩写

### D2. Pinia 状态持久化策略
- `auth.token` → localStorage `'accessToken'`（页面刷新不丢登录）
- `auth.userInfo`、`auth.families` → 不持久化，每次 refresh 调 `/api/auth/me` 重新拉取（保数据新鲜）
- `family.currentFamily.id` → localStorage `'currentFamilyId'`（刷新后 X-Family-Id 不丢）
- `family.currentFamily.*` 其他字段、`family.members` → 不持久化，用 currentFamilyId 拉详情后填

> 理由：token 丢了得重新登录很影响老人体验，所以持久化；用户信息/家庭信息可能变（改昵称/角色/成员），所以每次进页面从后端拉。

### D3. client.js 拦截器 vs Pinia 的循环依赖解决
- **不能**在 client.js 顶层 `import { useAuthStore } from '../stores/auth'` → 会循环依赖（stores 可能 import router/api/client）
- 解决方案：
  - 请求拦截器读 localStorage 原生取 `'accessToken'`，不走 authStore
  - X-Family-Id 读 localStorage 原生取 `'currentFamilyId'`，不走 familyStore
  - 401 跳登录用 `window.location = '/login'`，不用 router.push（避免引入 router）
  - 中文提示用 `import { ElMessage } from 'element-plus'`（Element Plus 全局注册，稳定可用）

### D4. DFA 按钮渲染 + 防御性校验双重策略
- **渲染层**：`canTransition(current, target)` 返回布尔，`v-if` 控制按钮是否显示
- **点击层**：transition 函数内部再调用一次 canTransition，true 才调 api；false ElMessage.error('任务状态不能这样切换')
- 理由：避免快速点击/浏览器缓存导致 UI 状态与实际后端不一致，保护 DFA 不可破坏

### D5. 路由守卫与 public 路由
- `/login`：meta.public = true，无 token 可访问，有 token 直接跳 /tasks
- `/` 容器及其所有子路由：meta.requiresAuth = true（全局守卫默认）
- 守卫流程：`to.meta.public → 放行；无 token → redirect /login；有 token → 放行；有 token 访问 /login → redirect /tasks`
- 不做细粒度角色路由守卫（OWNER/ADULT/CHILD）—— 角色校验放接口层，后端返回 403 时 ElMessage 提示即可，路由层不用重复

### D6. 分类删除的两阶段策略
1. 调 `categoriesApi.del(id)`，成功→ ElMessage.success('删除成功')
2. 若失败→ ElMessage 提示「这个分类下还有任务，需要先合并到其他分类」，然后弹出「合并并删除」对话框：
   - 下拉选目标分类
   - 确认→调 `categoriesApi.del(id, mergeToId)`（query `?mergeTo=`）
   - 或改为 `categoriesApi.merge(id, mergeToId)` + `categoriesApi.del(id)`，取决于后端契约

策略目的：后端可能先拒绝再提示，也可能直接接受 mergeTo；两阶段不依赖后端具体行为，两端契约都能兜住。

## 数据流向

### 登录
```
用户填 identity+密码 → LoginView.login() → authApi.login({identity, password})
  → client 响应拦截器解 {code=0, data={token, userInfo, families}}
  → LoginView: authStore.setToken(data.token).setUserInfo(data.userInfo).setFamilies(data.families)
  → families[0] 若存在: familyStore.setCurrent(families[0])
  → router.push('/tasks')
```

### 任务列表（family header 注入）
```
TaskView onMounted → tasksApi.list({status, categoryId, source, keyword, dueBefore})
  → client 请求拦截器: 从 localStorage 取 accessToken → X-Access-Token
                       从 localStorage 取 currentFamilyId → X-Family-Id (tasks** 命中)
                       若 currentFamilyId 空 → ElMessage 提示「请先选择一个家庭」+ window.location='/families'
  → 返回 tasks[] → 渲染卡片（按 5 值 DFA 显示按钮）
```

### 切换家庭
```
Header el-select change → familyStore.setCurrent(family)
  → localStorage.currentFamilyId = family.id
  → 触发 (emit currentChanged 或 watch familyStore.$state) → 当前 View refetchData()
  → 下一次 tasks/categories 请求自动带新 X-Family-Id
```

### 接受邀请
```
InviteView input code → toUpperCase() → inviteApi.accept({code})
  → 成功 → ElMessage.success('加入成功') + router.push('/families')
  → 后端 404 INVITE_NOT_FOUND → 拦截器提示「邀请码不存在或已过期，请让家人重新生成」
  → 后端 400 INVITE_EXPIRED → 「这个邀请码已经超过 7 天过期了，请让家人重新生成」
  → 后端 400 INVITE_COUNT_FULL → 「这个邀请码次数已用完，请让家人重新生成」
  → 后端 409 INVITE_ALREADY_MEMBER → 「您已经是这个家庭的成员了」
```

## 老人易用性 checklist
- [x] 登录/注册输入框大（size=large），按钮全宽
- [x] 密码框 show-password 可选切换明文
- [x] 任务状态 5 种颜色 + 中文标签，不用英文缩写
- [x] 错误提示不含 technical jargon，全中文
- [x] 邀请码输入框自动转大写，老人不用切换输入法
- [x] emoji 图标 + 中文文字双保险，单看图也能明白
- [x] 字号统一 ≥16px body（细节优化，另起 change）。**已实现（2026-09-26 复验）**：`frontend/src/assets/styles.css` 提供 S/M/L 三档基准字号 16/18/21px，body 最小 16px 达标。
- [ ] 按钮最小高度 44px（细节优化，另起 change）。**部分实现，仍差 2px 未达标**：`.el-button { min-height: 42px }`（large 48px、submit 56px 已达标），基准按钮未满足 44px 目标，保留未勾。
- [x] 邀请码复制后弹 toast「复制成功，把邀请码发给家人吧」提示。**已实现（2026-09-26 复验）**：`InviteView.vue` 的 `copyCode()` 成功时 `ElMessage.success('邀请码已复制')`，失败时降级提示手动复制；实际文案与本文案不同但功能达成。

## 技术风险与缓解
| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| 后端契约字段不一致（login identity vs email+phone） | 中 | 中 | LoginView 同时兼容 identity / email / phone 三种 key，命中任意一个就赋值 |
| 后端 categories.merge/delete 契约不一致 | 中 | 低 | 两阶段策略兜住；必要时联调改 1-2 行 API 调用 |
| npm run build 首次加 vue-router/pinia 报依赖冲突 | 低 | 高 | 用 `npm install --legacy-peer-deps`，当前都是 Vue 3 主流版本，冲突概率低 |
| X-Family-Id 缺失导致 tasks/categories 跳转 | 中 | 中 | client.js 拦截器兜底跳 /families，Header 提供家庭选择下拉，用户能手动恢复 |
| LocalStorage 被浏览器禁用 | 极低 | 中 | 降级：内存 sessionStorage；极端情况每次登录都要重新来，不崩 |