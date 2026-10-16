# Design: Change 9 - 前端移动端适配（响应式改造）

## Context

- 前端栈：Vue 3 + Vite + Element Plus + Pinia + Vue Router 4，无任何响应式断点（仅 prefers-contrast/reduced-motion 两条无障碍 media query）。
- 现有布局：`AppLayout.vue` = el-header(64px) + el-aside(220px 固定) + el-main；老人友好三档字号（S/M/L，rem 基准）与 42px+ 大按钮已是全局规范，天然适配移动端触控。
- 53 个前端单测（vitest + happy-dom）必须保持全绿；`npm run build` 无报错是验收基线。
- 动机与范围见 proposal.md - Why / 非目标。

## Goals / Non-Goals

**Goals:**
- 一套代码同时服务 PC 与手机，≤768px 断点切换布局形态
- 底部 Tab 导航 + 更多聚合页的信息架构落地
- TaskView 高频列表移动端卡片化
- 现有 53 个测试零破坏，新增移动端行为有测试覆盖

**Non-Goals:**
- 不换组件库、不建独立移动端应用、不做平板中间断点（见 proposal 非目标）
- 不改后端任何代码

## Decisions

### D1: 纯 CSS 断点 + `useIsMobile()` composable 双轨检测

**问题**：布局切换既需要 CSS（样式），也需要 JS（条件渲染组件树，如 BottomTabBar 只在移动端挂载、el-table vs 卡片二选一）。

**方案**：
- CSS 层：`styles.css` 全局断点 `@media (max-width: 768px)`，AppLayout 内 el-aside 用 `display:none` 隐藏
- JS 层：新增 `src/composables/useIsMobile.js`（`window.matchMedia('(max-width: 768px)')` + `addEventListener('change')` 响应式 ref），供条件渲染使用
- 两处断点值定义为 `styles.css` 顶部 CSS 变量注释 SSOT + composable 常量，保持 768 一致

**取舍**：Alternatives——①纯 CSS 无法解决 el-table/卡片二选一的组件级切换；②引入 `vue-use` 的 useMediaQuery 违反「不新增依赖」约束。自写 composable 约 15 行，风险最低。

### D2: 底部导航信息架构 = 高频 3 项 + 「更多」聚合

**问题**：现有侧边栏 7 项，底部 Tab 超过 5 项可点击性急剧下降。

**方案**：按使用频率分层——首页/我的任务/AI 拆解（家庭日常高频）直接上 Tab；模板管理/自动化/分类管理/家庭管理（配置管理性质，低频）进 `/more` 聚合页。`/more` 复用 AppSider 同款 emoji + 中文文案，降低老人学习成本。

**取舍**：Alternatives——①7 项全放底部（拥挤、点击目标 <40px）；②汉堡抽屉菜单（老人发现性差，需要多一步）。聚合页方案与现有「菜单可见即所得」的老人友好原则最一致。

### D3: TaskView 移动端卡片 = 组件内双模板 `v-if="isMobile"`，不改数据层

**问题**：el-table 在 375px 必然横向溢出。

**方案**：TaskView 内 `v-if="isMobile"` 渲染卡片列表（复用现有任务卡片的语义色/按钮/`canTransition` 逻辑），`v-else` 保持现有 el-card 网格。数据获取、store、API 层零改动。

**取舍**：Alternatives——①给 el-table 套横向滚动容器（体验差，老人不会横向滑）；②重写为自适应组件（改动面大，回归风险高）。双模板改动最小且与 D1 composable 自然组合。其他低频页（分类/模板/自动化）本期不做卡片化，仅靠全局触控与字号规则保证可读可点。

### D4: BottomTabBar 固定定位与安全区适配

**方案**：`position: fixed; bottom: 0` + `padding-bottom: env(safe-area-inset-bottom)`（iOS 微信 WebView 底部安全区），AppLayout 移动端主内容区预留等高 padding，避免内容被 Tab 遮挡。Header 保持 64px 不动。

**取舍**：微信内置浏览器（WKWebView/X5）对 `100vh` 的地址栏收放处理不一致，`env(safe-area-inset-bottom)` + 内容区预留是经验上最稳的组合；不做 JS 动态高度计算（复杂度不值当）。

## Risks / Trade-offs

- [Element Plus 弹窗（el-dialog/el-message-box）在 375px 下宽度溢出] → 全局断点内对 dialog 加 `width: 92% !important; margin: 0 auto` 兜底；不改逐个弹窗
- [el-select 下拉在移动端键盘遮挡] → 本期接受；表单页属于低频管理页，深度优化不做（非目标）
- [matchMedia 在 happy-dom 测试环境的兼容性] → composable 内做 `typeof window.matchMedia === 'function'` 守卫，测试环境默认返回桌面形态；移动端行为测试用组件级 mock 驱动
- [双模板导致 TaskView 模板体积增大] → 移动端卡片抽为独立子组件 `TaskCardMobile.vue`，主视图保持可读

## Migration Plan

1. 纯前端渐进改动，无数据迁移、无接口变更
2. 部署即生效（Vite 构建产物替换）；回滚 = 回退镜像版本，无数据影响
3. 按 Task 顺序合入：布局框架 → 页面卡片化，每个 Task 后 `npx vitest run` 全绿 + `npm run build` 无错

## Open Questions

（无）
