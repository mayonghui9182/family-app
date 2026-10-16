# Check List: change6-frontend-views

> 收尾验收：2026-09-05（master，合并提交 c86f4d3）
> 本轮实测命令：
> - frontend：`npx vitest run` → **58 passed (11 test files)**；`npm run build` → ✓ built in 3.74s
> - backend：`cd backend && ./gradlew test` → **230 tests / 0 failures / 0 errors**（AgentProxyController C-25~C-34 代理在套件内，Change 7 guard 逻辑保留）
>
> **说明**：X8 sseReconnect / X4 路由 / X3 MetricCard / API SDK 等核心逻辑有自动化测试；Task 2 Step 1 规划的 4 个视图级组件测试（Dashboard3/Chat2/Templates1/Automation1 共 7 用例）未独立成文件，视图以 build 通过 + 开发期联调为证，组件单测补写列入后续工作（与 change8 遗留项一并承接）。

| # | Item | Result | Evidence |
|---|------|--------|----------|
| 1 | X8 SSE exponential backoff: BASE_DELAY 3s, MAX_DELAY 24s, fail*2, slow_mode 30s after 3 failures | ✅ PASS | tests/utils/sseReconnect.test.js 4 用例，vitest 58/58 绿 |
| 2 | X4 root route "/" renders DashboardView (not TasksView) | ✅ PASS | tests/utils/routerDashboard.test.js 2 用例（/ 重定向 /dashboard + dashboard 路由指向 DashboardView）+ build 通过 |
| 3 | X3 MetricCard color thresholds: <50%=red, 50-74%=yellow, >=75%=green | ✅ PASS | tests/utils/MetricCard.test.js 7 用例（45% 红 / 70% 黄 / 90% 绿 + 非百分比/delta 边界）全绿 |
| 4 | DashboardView: 3 metric cards + 10-task mini-table + AI quick-decompose card + manual summary(role-gated OWNER/ADULT) | ⚠️ 代码交付 | views/DashboardView.vue 在库，build 通过；计划 Dashboard3 视图用例未独立成文件（核心 MetricCard 逻辑由 #3 覆盖） |
| 5 | ChatView: stepper(CN) + SSE streaming via fetch+ReadableStream + cancel btn | ⚠️ 代码交付 | views/ChatView.vue + utils/sseReconnect.js（fetch 基建设施由 #1 测试覆盖）；Chat2 视图用例未写；注：SSE 经 /api/agent 代理转发，change8 起会话流改由 DecomposeChatPanel + Mock/真链路承接 |
| 6 | TemplatesView: 3 tabs(list/history, create, pick-for-task) + star/delete/select | ⚠️ 代码交付 | views/TemplatesView.vue 在库，build 通过；Templates1 用例未写；C-27~C-31 模板端点后端 230 套件绿 |
| 7 | AutomationView: 3 MetricCards + X2 warning alert + summary log table + manual trigger(force toggle) | ⚠️ 代码交付 | views/AutomationView.vue 在库，build 通过；Automation1 用例未写；A-2 触发/总结指标后端 change5 已交付 |
| 8 | TaskView refactor: Tab2 AI Decompose form(title/category/notes)->ChatDialog | ⚠️ 代码交付 | TaskView.vue AI 入口在库（change8 进一步演化为 DecomposeChatPanel 双按钮）；开发期联调通过，无自动化断言 |
| 9 | InviteView: aria-labels added, canvas scales with font store | ⚠️ 代码交付 | views/InviteView.vue aria-label/font-store 兼容在库；snapshot 测试未写 |
| 10 | Vitest suite passes(all new + existing tests) | ✅ PASS | `npx vitest run` → Test Files 11 passed, Tests **58 passed** |
| 11 | Client interceptor extended: X-Family-Id sent for /agent/* paths | ✅ 代码+间接覆盖 | api/client.js 拦截器：除 /auth/** 外全部注入 X-Family-Id（含 /agent/*）；tests/api/client.test.js + family.test.js 链路间接覆盖，无独立头断言 |
| 12 | Backend proxy C-25..C-34: ./gradlew test baseline maintained | ✅ PASS | Java **230/0/0**；AgentProxyController 代理端点 + Change 7 guard 逻辑保留（合入 change8 后回归绿） |
| 13 | Vite build passes(Vite prod build) | ✅ PASS | `npm run build` ✓ 3.74s，无错误 |
| 14 | Full Chinese UI + elder-friendly: min-height 56px buttons, WCAG AA contrast | ⚠️ 部分达标 | 全中文 emoji UI 在库；min-height/对比度/lighthouse 未量化验收 |
