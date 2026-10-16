# Check List: change9-mobile-responsive

> 收尾验收：2026-09-06（master 581e1ba 之后）
> 本轮实测命令：
> - frontend：`npx vitest run` → **77 passed (16 test files)**（58 基线 + change9 新增 19 用例：Task1 11 + Task2 8），桌面 53 基线零破坏
> - frontend：`npm run build` → ✓ built in 3.65s，无报错无告警（最大 chunk 456.19 kB / gzip 162.63 kB）
>
> **交付范围**：纯前端，不改后端。useIsMobile composable（768px SSOT）+ BottomTabBar（4 Tab + 安全区）+ MoreMenuView（/more 4 入口）+ AppLayout v-if 双形态 + styles.css 移动断点块（el-dialog 92% / 触控 44px）+ TaskView 移动端卡片化（TaskCardMobile + taskStatus 共享 DFA）。
> **遗留（非阻塞）**：浏览器 DevTools 375px/1280px 双档人工目视未执行（无 GUI 环境），自动化已覆盖分支渲染与路由跳转；低频页（分类/模板/自动化）本期不做卡片化（design D3 非目标）。

| # | 项 | 结果 | 证据 |
|---|---|---|---|
| 1 | useIsMobile：matchMedia true/false 两态 + change 监听响应（3 用例） | ✅ PASS | tests/composables/useIsMobile.test.js 3/3 |
| 2 | AppLayout 响应式：isMobile=true 无 el-aside + 有 BottomTabBar + main 移动端 padding；false 桌面零回归（2 用例） | ✅ PASS | tests/layouts/AppLayoutResponsive.test.js 2/2 |
| 3 | BottomTabBar：4 Tab 渲染 + 当前路由高亮(aria-current) + 点击跳转（3 用例） | ✅ PASS | tests/components/BottomTabBar.test.js 3/3 |
| 4 | MoreMenuView：4 低频入口渲染 + 点击跳转（2 用例） | ✅ PASS | tests/views/MoreMenu.test.js 2/2 |
| 5 | /more 路由注册（requiresAuth 继承布局 + 懒加载） | ✅ PASS | tests/router/moreRoute.test.js 1/1 |
| 6 | TaskCardMobile：标题/中文状态标签/语义色 class/指派人/截止日 | ✅ PASS | tests/components/TaskCardMobile.test.js 用例1-2 |
| 7 | TaskCardMobile：按钮组随状态矩阵（PENDING 三按钮 / COMPLETED 终态无流转按钮） | ✅ PASS | TaskCardMobile.test.js 用例3a/3b |
| 8 | TaskCardMobile：点击"完成" emit transition(task, COMPLETED) | ✅ PASS | TaskCardMobile.test.js 用例4 |
| 9 | TaskView 双模板：isMobile=true 卡片列表无网格；false el-card 网格无移动卡；两路径同数据源（2 用例） | ✅ PASS | tests/views/TaskViewMobile.test.js 用例1-2 |
| 10 | TaskView 移动端点击"完成" → tasksApi.updateStatus(id, status) 与桌面同一守卫 | ✅ PASS | TaskViewMobile.test.js 用例3（toHaveBeenCalledWith(1,'COMPLETED')） |
| 11 | 全量 vitest 绿（基线 53 零破坏） | ✅ PASS | **77 passed / 0 failed (16 files)** |
| 12 | Vite 生产构建通过 | ✅ PASS | `npm run build` ✓ 3.65s |
| 13 | 全局移动断点：el-dialog 92% 不溢出；触控目标 ≥44px；安全区 env(safe-area-inset-bottom) | ✅ 代码交付 | styles.css 追加 @media(max-width:768px) 块 + BottomTabBar fixed+安全区 + AppLayout .app-main-mobile 底部预留 76px |
| 14 | 断点 SSOT 一致（CSS 768 与 composable MOBILE_BREAKPOINT 768） | ✅ PASS | useIsMobile.js 常量 + styles.css 注释双向标注 |
