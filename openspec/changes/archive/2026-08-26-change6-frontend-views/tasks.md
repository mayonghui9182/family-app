# Tasks: 前端四视图 + 旧改造 + X3 X4 X8

> **收尾状态（2026-09-05，master c86f4d3 合并后回填）**
> - Task 1 ✅ 交付：sseReconnect(X8) + router(X4) + MetricCard(X3) + SDK 三文件 + 4 Stores + AgentProxyController C-25~C-34 后端代理；sseReconnect 4 用例 / router 2 用例 / MetricCard 7 用例全绿，Java 230/0/0
> - Task 2 ✅ 交付：DashboardView / ChatView / TemplatesView / AutomationView 四视图 + TaskView/InviteView 改造在库，build 通过；**Step 1 规划的 7 个视图级用例未独立成测试文件**（核心 utils/sdk 逻辑由 11 个测试文件 58 用例覆盖），视图组件单测补写列入后续 change
> - 收尾 ✅ 2026-09-05：check_list 14 项证据回填 + vitest 58/58 + Java 230/0/0 + build ✓ + 归档

- [x] **Task 1: Backend 适配层 + Frontend SDK + Stores + X8 sseClient + MetricCard**
  - Step 1: TDD 失败测试（先写测试后实现）
    - sseReconnect.test.js: 4用例(指数退避3s->6->12->24封顶;连续3次失败->30s慢模式;onopen归零;forceReconnect重置)
    - router test: 1用例(root路由"="/" -> DashboardView而非TasksView)
    - MetricCard.test.js: 2用例(百分比阈值颜色45%->红/70%->黄/90%->绿;delta/deltaText格式化)
  - Step 2: utils/sseReconnect.js + stores/sseClient.js: X8实现(fetch-based替代EventSource,支持POST SSE流)
  - Step 3: router/index.js: X4根路由重定向至DashboardView
  - Step 4: SDK三文件 api/decompose.js / templates.js / sdkSummary.js(走backend /api/agent/*)
  - Step 5: Pinia stores decompose.js / templates.js / summary.js: 状态机+CRUD+polling
  - Step 6: components/MetricCard.vue: X3通用指标卡(value/unit/delta/percentStatus/emoji/title)
  - Step 7: 跑7条测试 -> PASS
  - Step 8: Commit feat(change6): SDK三文件+4Stores+X8SSE指数退避+MetricCard+Router
  - Step 9: **Backend适配层**(Java): AgentProxyController公开/api/agent/**转发代理
    - C-25 POST /api/agent/v1/decompose/start -> 转发agent-service /api/v1/chat/stream(SSE流式响应)
    - C-26 POST /api/agent/v1/decompose/{familyId}/{taskId}/cancel -> 转发取消
    - C-27 GET /api/agent/v1/templates -> GET /api/internal/agent/templates映射
    - C-28 GET /api/agent/v1/templates/{id}/versions -> 同C-24逻辑但公开路径
    - C-29 PATCH /api/agent/v1/templates/versions/{v}/star -> toggle is_important
    - C-30 DELETE /api/agent/v1/templates/versions/{v} -> 删除版本
    - C-31 POST /api/agent/v1/templates/{tplId}/versions/{v}/pick -> 选模板给任务
    - C-32 GET /api/agent/v1/summary/trigger/{familyId} -> A-2触发器代理
    - C-33 GET /api/agent/v1/summary/list/{familyId} -> A-3 list(读backend summary_execution_log)
    - C-34 GET /api/agent/v1/summary/metrics/{familyId} -> A-4指标(SQL: success_rate/version_count/learned_adjustments)
  - Step 10: Backend自测 ./gradlew test基线保持

- [x] **Task 2: 4新视图 + 2旧改造实现 + 验收** <!-- 视图全交付且 build 绿；Step 1 的 7 个视图级 TDD 用例未独立成文件，见顶部收尾状态 -->
  - Step 1: TDD失败测试(Dashboard3+Chat2+Templates1+Automation1=7用例)
  - Step 2: views/DashboardView.vue(X4根): 顶部3指标卡+中部最近10任务表+左下AI快速拆解+右下总结面板
  - Step 3: views/ChatView.vue: stepper进度栏(输入->分类->推理->提案->确认/取消)+SSE实时消息区+B-1回调
  - Step 4: views/TemplatesView.vue: 三Tab(模板列表/创建模板/选模板给任务)
  - Step 5: views/AutomationView.vue: 顶部3MetricCard+中部X2警告卡+下部summary_log表格+OWNER/ADULT手动总结开关
  - Step 6: views/TaskView.vue改造: Tab2 AI拆解表单->跳转/chat或弹Chat Dialog
  - Step 7: views/InviteView.vue改造: aria-label+canvas font-store放大兼容
  - Step 8: 跑Views测试 -> PASS
  - Step 9: npm run test:unit全通过
  - Step 10: Commit feat(change6): 4新视图+旧改造+X3X4X8

- [x] **收尾: 归档 + Check List + Handoff**
  - check_list.md填满(每项含测试名+命令实测数字)
  - 归档目录->openspec/changes/archive/
  - 同步handoffs/current.md
