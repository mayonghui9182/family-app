# Tasks: 前端移动端适配（Change 9）

> 执行顺序：Task 1 → Task 2 → 收尾（顺序依赖；每步完成后 vitest 全绿 + build 无错再进下一步）

> **收尾状态（2026-09-06）**：Task 1/2 全量交付并归档。实测 **vitest 77 passed (16 files)**（58 基线 + 新增 19 用例）、`npm run build` ✓ 3.65s。人工 375px/1280px 双档目视因无 GUI 未执行，自动化已覆盖分支渲染与跳转。

- [x] **Task 1：响应式布局框架（断点 + 底部 Tab + 更多聚合页）**
  - **Step 1：TDD 失败测试**
    - useIsMobile.test.js：matchMedia mock 返回 true/false 两态 + 变更监听响应（3 用例）
    - AppLayoutResponsive.test.js：isMobile=true 时 el-aside 不渲染 + BottomTabBar 渲染；false 时侧边栏渲染 + 无 BottomTabBar（2 用例）
    - BottomTabBar.test.js：4 个 Tab 渲染 + 当前路由高亮 + 点击路由跳转（3 用例）
    - MoreMenu.test.js：4 个入口渲染 + 点击跳转对应路由 + X-Family-Id 上下文不受影响（2 用例）
    - router：`/more` 路由注册（requiresAuth）（1 用例）
  - **Step 2：实现**
    - `src/composables/useIsMobile.js`：matchMedia('(max-width: 768px)') + change 监听 + happy-dom 守卫（无 matchMedia 时返回桌面态 ref）
    - `src/components/BottomTabBar.vue`：fixed 底部 4 Tab（🏠 首页/📝 我的任务/🤖 AI 拆解/⋯ 更多）+ 当前路由高亮 + `env(safe-area-inset-bottom)` 适配
    - `src/views/MoreMenuView.vue`：4 个低频入口卡片（📋 模板管理 / 📈 自动化 / 🗂️ 分类管理 / 🏠 家庭管理），emoji + 中文，与 AppSider 文案一致
    - `src/layouts/AppLayout.vue`：接入 useIsMobile；移动端隐藏 el-aside + 主内容底部预留 Tab 等高 padding；桌面端 DOM 结构不变
    - `src/router/index.js`：新增 `/more`
    - `src/assets/styles.css`：全局 `@media (max-width: 768px)` 断点块（断点值 768 注释为 SSOT）+ el-dialog 移动端 `width: 92%` 兜底 + 触控目标 ≥44px 规则
  - **Step 3：验证**
    - `npx vitest run` ≥ 原 53 + 新 11 全绿；`npm run build` 无报错
  - **Step 4：Commit** `feat(change9/task1): 移动端响应式布局框架 + 底部Tab + 更多聚合页`

- [x] **Task 2：TaskView 移动端卡片化** <!-- TaskCardMobile + utils/taskStatus.js 共享 DFA；v-if/v-else 间禁止插注释节点（会打断配对）-->
  - **Step 1：TDD 失败测试**
    - TaskCardMobile.test.js：标题/状态中文标签/指派人渲染；按钮组随 `canTransition` 状态矩阵渲染；点击按钮调用 tasksApi.updateStatus（4 用例）
    - TaskViewMobile.test.js：isMobile=true 渲染卡片列表且无横向溢出容器；false 走原 el-card 网格；两条路径数据源相同（2 用例）
  - **Step 2：实现**
    - `src/components/TaskCardMobile.vue`：纵向卡片（状态 tag 语义色沿用、流转按钮竖排、点击守卫与桌面一致）
    - `src/views/TaskView.vue`：`v-if="isMobile"` 卡片列表 / `v-else` 现有网格；数据与 store 零改动
  - **Step 3：验证**
    - `npx vitest run` 全绿；`npm run build` 无报错
  - **Step 4：Commit** `feat(change9/task2): TaskView 移动端卡片形态`

- [x] **收尾：Change 9 验收 + handoff 交接** <!-- check_list 14 项回填；人工目视验收留待真机浏览器执行 -->
  - Step 1：手动验收（浏览器 DevTools 375px / 1280px 双档）：移动端 4 Tab 可用、更多页 4 入口可达、任务卡片无横向滚动、桌面端零回归
  - Step 2：`npx vitest run` + `npm run build` 输出贴入 change 目录 check_list.md
  - Step 3：同步 `.handoffs/current.md`：Change 9 状态 + 下一步指向 Change 11（公众号入口）
  - Step 4：用户确认后走 openspec-archive
