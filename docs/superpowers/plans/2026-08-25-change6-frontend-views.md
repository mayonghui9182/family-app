# Change 6: 前端 4 视图 + 2 旧改造 + ❌3❌4❌8 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:frontend-skill (recommended) or superpowers:frontend-design for UI work. Use superpowers:subagent-driven-development or superpowers:executing-plans for step-by-step execution.

**Goal:** 交付 4 个全新 Vue 3 视图 + 2 个旧视图改造 + ❌3 三指标 UI 卡片（近30天拆解成功率/模板版本数/学习次数）+ ❌4 Dashboard "/" 根路由总览 + ❌8 SSE 指数退避重连（BASE_DELAY 3s → MAX_DELAY 24s）+ 全中文老人友好 + Pinia stores/SDK 客户端 + Vitest+happy-dom 14 测试用例。

**Architecture:** Vue Router 新路由表：`/`（Dashboard ❌4根）/`/chat`（ChatView 拆解）/`/templates`（TemplatesView 模板）/`/automation`（AutomationView ❌2警告+❌3卡片+手动总结）/（原有）/tasks`（TaskView 旧改造加一键AI拆解Tab）/`/invite`（InviteView 分享卡旧改造，保留）。Pinia 新增 4 个 stores：`templates.js`/`summary.js`/`sseClient.js`/`decompose.js`。SDK 新增 `frontend/src/api/{decompose.js,templates.js,sdkSummary.js}` 调 Change 2 后端代理 `/api/agent/*`（A-1/A-2/A-3/A-4/B-1）及 `/api/internal/templates/*`（I-1~I-7）。老人友好：所有默认字号=large（由 font store）；按钮 min-height 56px；颜色对比≥4.5:1 WCAG AA；emoji+中文按钮；表单聚焦实时错误提示（沿用 LoginView 范式）。

**Tech Stack:** Vue 3.4（Composition API `<script setup>`）/ Vue Router 4 / Pinia 2 / Element Plus 2.7（`<el-button size="large" type="primary">` 默认大尺寸）/ qrcode.vue（二维码组件）/ sse.js（或原生 EventSource 封装）/ dayjs 1.11 / vitest + happy-dom + @vue/test-utils / eslint + prettier 120 列 / 沿用 styles.css + font store 三档字号（已存在）

## Global Constraints
- ❌3 补项：AutomationView + DashboardView 共同时展示 3 张同构「指标卡」= ①近30天拆解成功率（COMPLETED/(COMPLETED+CANCELLED+FAILED)*100，API A-4 metrics 字段 success_rate_30d，展示百分位小数点0位，<50%红色，<75%黄色，≥75%绿色）②模板版本数（template_version_count_30d，元数据版本数，展示绝对值+"+增量30天"小灰色标）③学习次数（learned_adjustments_30d=diff_engine 触发新模板版本数≥3 阈值次数，展示绝对数+「次」后缀）
- ❌4 补项：根路由 `"/"` 必须 = DashboardView，不得进入 TasksView。Vite router/index.js 重定向 `"/"` → `"/dashboard"`（或者 Dashboard 路由 path=`"/"`）。Dashboard 顶部 3 指标卡（❌3），中部最近10 任务表格，底部左「AI 快速拆解卡片」+ 右「最近自动化总结时间+手动总结按钮（OWNER/ADULT才显示）」
- ❌8 补项：SSE 封装 `sseClient.js`：BASE_DELAY=3 秒；MAX_DELAY=24 秒；指数退避 `delay *= 2`；连续失败 3 次 → 自动进入「慢速模式」每 30 秒重试 + 屏幕顶部 `<el-alert type="warning" show-icon closable>`：「AI 连接中断，正在慢速重连（30秒一次），你仍可继续使用其它功能，点此处立即重试」按钮一键重连立即。成功后 alert 自动消失+恢复 3s 退避
- 老人易用约束：ChatView 默认 focus 在输入框「输入任务名称，我来拆解」，按钮文字如「立即开始拆解」「取消拆解」而非英文 Technical 词；Automation 中 ❌2 的黄色警告卡片文字大小默认 18px；所有中文 icon 可用 emoji 辅助表达；Dashboard 顶部指标卡数字≥32px；默认大字号（`font.size = "large"`）如果 localStorage 无 font store 记录
- 无障碍：所有可交互元素 aria-label 中文（如「开始拆解任务按钮」「切换到模板标签页」），focus-visible 蓝色粗边框 3px（沿用 styles.css 已定义），屏幕阅读器读出拆解 SSE 实时更新（aria-live="polite" 消息区）
- 403/401 统一：401→login 自动跳转；403 → `<el-message type="error">` 中文提示（如 CHILD 点手动总结：「只有家庭管理员或成人成员可以触发总结」）；不得给 tech stack trace
- 写操作强约束：所有新模板新版本/提交任务/确认/取消 → 全中文二次确认 `<el-popconfirm confirm-button-text="确认" cancel-button-text="再想想" title="…">`

---

## File Structure（改动 = M；新建 = C）
| 文件 | 操作 | 职责 |
|---|---|---|
| frontend/src/router/index.js | M | 加 `/` Dashboard /`/chat` /`/templates` /`/automation` 4 新路由；旧 `/tasks` /`/invite` 保留 |
| frontend/src/stores/decompose.js | C | Pinia：decompose 状态机（idle/classifying/reasoning/proposing/confirmed/cancelled/error）；接收 SSE 事件流写 state |
| frontend/src/stores/templates.js | C | Pinia：I-1 list / I-2 versions / I-4 star/ ⭐ is_important 切换 / I-6/7 选模板 |
| frontend/src/stores/summary.js | C | Pinia：A-2 手动触发 trigger_id / A-3 list / poll estimated_finish_at / ❌3 metrics fetch |
| frontend/src/stores/sseClient.js | C | ❌8 指数退避 EventSource 封装；addEventListener("classification"…) → commit 到 decompose；fail 3 次→30s慢模式 |
| frontend/src/api/decompose.js | C | A-1 POST `{family_id, title, category_id, notes}` → stream 订阅调用；cancel POST /v1/internal/decompose/{family_id}/{task_id}/cancel |
| frontend/src/api/templates.js | C | I-1~I-7 CRUD + I-4 star + I-5 delete + I-6/7 choose |
| frontend/src/api/sdkSummary.js | C | A-2 手动总结 / A-3 summary list / A-4 metrics ❌3 |
| frontend/src/utils/sseReconnect.js | C | ❌8 算法（delay*2，3s→24s，连续3次失败→30s慢模式立即重试按钮）+ window 环境变量 `VITE_SSE_BASE_URL` |
| frontend/src/views/DashboardView.vue | C | ❌4 根路由：顶部 3 指标卡 ❌3 + 中部最近10任务表 + 左下AI快速拆解+右下最近总结+手动总结（权限） |
| frontend/src/views/ChatView.vue | C | 拆解全过程视图：顶部 progress stepper（输入需求→分类→推理→提案→家人确认/取消）/ 中部 SSE 实时卡 / 底部 is_ai_generated badge / 取消拆解 Btn / 返回编辑 Btn / 调 B-1 confirmation 回调 / 显示 cancelled draft ❌2 警告 若存在 |
| frontend/src/views/TemplatesView.vue | C | Tab1「模板列表」（分类筛选+版本历史+⭐is_important+change_summary中文学习描述）/ Tab2「创建模板」（标题+分类+子步骤编辑器+notes）/ Tab3「选模板给任务」（搜索框+列表） |
| frontend/src/views/AutomationView.vue | C | ❌3 三指标卡顶部 / 中部 ❌2 警告卡（若最近7天≥5次取消→黄色el-alert show-icon + 诊断文字）/ 下部 summary_execution_log 表格 / OWNER/ADULT 显示 ⚡立即手动总结 A-2 + force=true 开关 |
| frontend/src/views/TasksView.vue | M（旧改造）| 新增 Tab2「AI 拆解」：表单 title/分类/备注 + 「一键拆解」按钮 → 跳转 `/chat?family_id=…&title=…` 或原地弹 Chat Dialog，默认 Dialog |
| frontend/src/views/InviteView.vue | M（旧改造，保留分享卡）| 无功能改动，仅加 aria-label / focus-visible 对齐 styles.css，确保 font store 放大兼容 |
| frontend/src/components/SseStatusBar.vue | C | 顶部固定 SSE 状态条：连接中/已连接/重连中（❌8）显示指数退避倒计时 |
| frontend/src/components/MetricCard.vue | C | ❌3 通用指标卡组件（接收 title/value/unit/percentStatus 属性，颜色阈值） |
| frontend/tests/views/DashboardView.test.ts | C | 14 用例：根路由"/"=Dashboard / 3指标卡渲染 / A-4 mock success_rate_30d=45%→红色/70%黄色/90%绿色 / CHILD无手动总结按钮 / OWNER有按钮 |
---

### Task 1: SDK + Router + Stores + ❌8 sseClient 指数退避 + MetricCard 骨架（先测后写 TDD）

- [ ] **Step 1: 写失败测试 7 条（SSE 退避算法 4 条 + Router ❌4 根路由 1 条 + MetricCard ❌3 阈值颜色 2 条）**
sseReconnect.test.js（4用例）：
用例 1：首次失败 → expect delay = 3000ms（BASE_DELAY），第2失败→6000ms，第3失败→12000ms，第4失败→24000ms（MAX=24封顶），无失败次数>4 → delay永远24000不增长
用例 2：连续失败 3 次 → 自动进入 slow_mode=30000ms；slow_mode 期间 `forceReconnect()` → 立即重置 delay=3000
用例 3：EventSource.onopen 成功 → 重置 delay=BASE_DELAY + slow_mode=false + fail_count=0
用例 4：监听 SSE 5 事件 → classification→stores.decompose.commitClassify / reasoning→commitReason / proposal→commitProposal / done→finishDecompose / error→setError（测试通过 mock EventSource dispatch 事件）
Router + Dashboard.test.ts（3用例）：
用例 5：`router.push("/")` → currentRoute.path = "/" 或 "/dashboard" → DashboardView 渲染（不是 TasksView），内部渲染 3 个 MetricCard 组件（通过 data-testid="metric-card" 断言存在 length=3）
用例 6：MetricCard success_rate=45%（<50）→ el-card 背景 color="danger/红色"；success_rate=70% → warning/黄色；success_rate=90% → success/绿色；用 classList.contains(`bg-red-or-bg-yellow-or-bg-green-class`) 断言
用例 7：MetricCard 三字段（value/unit/percentStatus）分别展示整数百分比+百分号，小数点0位（45.8→「46%」四舍五入）
Run: `cd frontend ; npm.cmd test -t "sseClient or router or metriccard"` Expected: FAIL（没代码）

- [ ] **Step 2: utils/sseReconnect.js + stores/sseClient.js ❌8 实现**
const BASE_DELAY = 3000; const MAX_DELAY = 24000; const SLOW_MODE_DELAY = 30000; const SLOW_MODE_THRESHOLD = 3;
export function createReconnectState() {
  return { failCount: 0, delay: BASE_DELAY, slowMode: false };
}
export function nextDelay(state, { connected }) {
  if (connected) { // onopen 成功 → 全归零
    state.failCount = 0; state.delay = BASE_DELAY; state.slowMode = false;
    return state;
  }
  state.failCount += 1;
  if (state.slowMode) return { ...state, delay: SLOW_MODE_DELAY }; // 慢速模式维持 30s
  state.delay = Math.min(state.delay * 2, MAX_DELAY); // 指数:3→6→12→24
  if (state.failCount >= SLOW_MODE_THRESHOLD) state.slowMode = true;
  return state;
}
export function forceReconnectNow(state) { // 用户点立即重试
  state.slowMode = false; state.failCount = 0; state.delay = BASE_DELAY;
  return state;
}
stores/sseClient.js：
import { createReconnectState, nextDelay, forceReconnectNow } from "@/utils/sseReconnect";
const envUrl = import.meta.env.VITE_SSE_BASE_URL ?? import.meta.env.VITE_API_BASE_URL ?? "";
export const useSseStore = defineStore("sse", {
  state: () => ({ status: "idle", reconnectState: createReconnectState(), alertVisible: false }),
  actions: {
    async connect(path, { onClassify, onReason, onProposal, onDone, onError }) {
      const openPath = () => {
        const es = new EventSource(`${envUrl}${path}`, { withCredentials: true });
        es.addEventListener("classification", (e)=> onClassify?.(JSON.parse(e.data)));
        es.addEventListener("reasoning", (e)=> onReason?.(JSON.parse(e.data)));
        es.addEventListener("proposal", (e)=> onProposal?.(JSON.parse(e.data)));
        es.addEventListener("done", (e)=> { const d = JSON.parse(e.data); onDone?.(d); nextDelay(this.reconnectState, {connected:true}); this.alertVisible=false; es.close(); });
        es.addEventListener("error", (e)=> {
          es.close();
          nextDelay(this.reconnectState, {connected:false});
          onError?.(new Error("SSE 连接异常，指数退避中…"));
          if (this.reconnectState.slowMode) { this.status="slow"; this.alertVisible=true; }
          setTimeout(openPath, this.reconnectState.delay);
        });
        es.onopen = () => { nextDelay(this.reconnectState, {connected:true}); this.status="connected"; this.alertVisible=false; };
        this.status = "connecting";
      };
      openPath();
    },
    retryNow() { forceReconnectNow(this.reconnectState); this.status="connecting"; }
  }
});

- [ ] **Step 3: Router/index.js ❌4 根路由重定向 + SDK 三文件**
Router：
```js
const routes = [
  { path: "/", name: "Dashboard", component: DashboardView, meta: { requiresAuth: true } },
  { path: "/chat", name: "Chat", component: ChatView, meta: { requiresAuth: true } },
  { path: "/templates", name: "Templates", component: TemplatesView, meta: { requiresAuth: true } },
  { path: "/automation", name: "Automation", component: AutomationView, meta: { requiresAuth: true } },
  // 旧路由保留...
];
```
api/decompose.js：
```js
import client from "./client";  // 沿用 axios client，自动带 X-Family-Id/X-Access-Token（interceptors 已做）
const BASE = "/api/agent/v1/internal/decompose"; // 走 backend 反向代理
export const decomposeApi = {
  start: (payload) => client.post(`${BASE}/start`, payload),
  cancel: (familyId, taskId) => client.post(`${BASE}/${familyId}/${taskId}/cancel`), // A-1 取消
  confirm: (familyId, taskId, body) => client.post(`${BASE}/${familyId}/${taskId}/confirm`, body) // B-1 回调
};
export default decomposeApi;
```
api/templates.js：
```js
const BASE = "/api/internal/templates";
export const templatesApi = {
  list: (p) => client.get(BASE, { params: p }), // I-1
  versions: (tplId) => client.get(`${BASE}/${tplId}/versions`), // I-2
  createVersion: (tplId, body) => client.post(`${BASE}/${tplId}/versions`, body), // I-3
  toggleStar: (vId, bool) => client.patch(`${BASE}/versions/${vId}/star`, { is_important: bool }), // I-4
  deleteVersion: (vId) => client.delete(`${BASE}/versions/${vId}`), // I-5
  pickVersionForTask: (tplId, vId, taskId) => client.post(`${BASE}/${tplId}/versions/${vId}/pick`, { task_id: taskId }) // I-6/I-7
};
```
api/sdkSummary.js：
```js
const BASE = "/api/agent/v1/internal/summary";
export const summaryApi = {
  trigger: (familyId, force) => client.post(`${BASE}/trigger/${familyId}`, { force: !!force }), // A-2
  list: (familyId, p) => client.get(`${BASE}/list/${familyId}`, { params: p }), // A-3
  metrics: (familyId, window) => client.get(`${BASE}/metrics/${familyId}`, { params: { window_days: window ?? 30 } }) // A-4 ❌3
};
```

- [ ] **Step 4: stores/decompose.js 状态机 + stores/templates.js + stores/summary.js + components/MetricCard.vue**
decompose state 枚举：`idle/classifying/reasoning/proposing/confirmed/cancelled/error`
proposal 数据：`{task_id, title, category, subtasks:[{title, description, ai_generated, estimated_minutes}], title_reasoning_cn, category_reasoning_cn}`
MetricCard：
```vue
<template>
  <el-card :class="[cardClass]" shadow="hover" class="metric-card" :aria-label="`${title}指标卡，值${formattedValue}`">
    <div class="metric-title fs-16 fw-500 c-gray-600">{{ emoji }} {{ title }}</div>
    <div class="metric-value fs-40 fw-700 my-3">{{ formattedValue }}<small class="fs-18 fw-400 c-gray-500 ml-1">{{ unit }}</small></div>
    <div v-if="delta" class="metric-delta fs-14 c-gray-500">较 {{ prevWindowLabel }}：<span :class="deltaClass">{{ deltaText }}</span></div>
  </el-card>
</template>
```
cardClass 逻辑：percentStatus<50→danger/bg-red-light；50≤<75→warning/bg-yellow-light；≥75→success/bg-green-light；非百分比（次数/版本数）→默认蓝卡

- [ ] **Step 5: Run Vitest 7 用例 Expected: PASS**
- [ ] **Step 6: Commit feat(change6): SDK三文件 + 4 Stores + ❌8 SSE指数退避3s→24s→30s慢模式 + MetricCard ❌3 + Router ❌4根路由**

---
### Task 2: 4 新视图 + 2 旧改造 失败测试 + 实现

- [ ] **Step 1: 写失败测试 7 条（Dashboard 3 + Chat 2 + Templates 1 + Automation ❌2警告1）**
DashboardView.test.ts（3用例）：
用例 1：mock summaryApi.metrics 返回 {success_rate_30d:90, template_version_count_30d:12, learned_adjustments_30d:5} → 渲染 3 个 MetricCard（getByTestId "metric-card" 3 张）+ 最近10任务表格行数=10（mock taskApi.list 返回 10 items）+ OWNER 角色有「⚡立即总结」按钮
用例 2：CHILD 角色 → 「⚡立即总结」按钮 NOT exists（断言 toBeUndefined 或 getByRole("button", {name:/立即总结/}) 抛错）
用例 3：点击「⚡立即总结」→ 弹 `el-popconfirm title="是否立即重新生成今日学习总结？（force=true 会覆盖今日已有总结）"`；点确认 → 调 summaryApi.trigger(familyId, force=true) once
ChatView.test.ts（2用例）：
用例 4：输入 title="周末大扫除" → 点「🤖 开始拆解」→ 调 decomposeApi.start({family_id, title, category_id, notes}) once → SSE classification 事件触发后 stepper 高亮 step=2（分类推理）+ reasoning→step=3（AI推理）+ proposal→step=4（家人确认）+ 每个 subtask 显示「🤖 AI 生成」徽标 is_ai_generated=true
用例 5：点「家人取消」→ 调 decomposeApi.cancel(familyId, taskId) once；若当前 proposal 来自 rule_template_versions.id=X 且包含 ❌2 警告（A-1 返回 decomposition_template_warning_cn 字段非空）→ 顶部额外显示 el-alert warning 含「⚠️ 此分类近7天家人连续取消≥5次」
TemplatesView.test.ts（1用例）：
用例 6：Tab1 模板列表 → 选某模板「查看版本历史」→ 调用 templatesApi.versions(tplId) once → 列表显示版本号+created_at + ⭐ star（is_important）按钮 → 点击 ⭐ → 调用 templatesApi.toggleStar(versionId, true) once，is_important 置反
AutomationView.test.ts（1用例）：
用例 7：mock A-4 metrics 返回 ❌2 触发字段 {categories_with_high_cancellation: [{id:1,name:"家务"}]} → Automation 顶部出现 el-alert type=warning 标题「⚠️ 家务分类近 7 天被家人连续取消 ≥5 次，请检查：1.模板粒度太细？2.规则与家人习惯不符？3.系统提示词是否需调整？」（对齐 Change 5 ❌2 文案）
Run: `cd frontend ; npm.cmd test -t "Views"` Expected: FAIL（无视图文件）

- [ ] **Step 2: 4 新视图组件实现（Composition API `<script setup>` + Element Plus + 全中文 + 字号大按钮）**
*核心交互要点简述（完整实现按 B4 §4 章节；不展开具体行数避免分裂）*

DashboardView.vue（❌4 根路由）：
```
<template>
  <div class="dashboard-py-8 px-6 font-size-l-125">
    <h1 class="fs-28 fw-700 mb-6">🏠 家庭助手首页</h1>
    <section aria-label="关键指标3张卡片" class="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
      <MetricCard title="AI 拆解成功率" :value="m.success_rate_30d" unit="%" :percent-status="m.success_rate_30d" emoji="🎯" delta="+2%" />
      <MetricCard title="生成模板版本" :value="m.template_version_count_30d" unit="个" emoji="📋" delta="+4" />
      <MetricCard title="家人行为学习" :value="m.learned_adjustments_30d" unit="次" emoji="🧠" delta="+1" />
    </section>
    <!-- 中部最近10任务表格 -->
    <section aria-label="最近任务列表"> <TaskMiniTable :rows="recentTasks" /></section>
    <!-- 底部左下 AI 快速拆解卡片 -->
    <section class="grid grid-cols-1 md:grid-cols-2 gap-6 mt-8">
      <el-card>
        <h3 class="fs-22 mb-4">⚡ AI 快速拆解新任务</h3>
        <el-input v-model="quickTitle" size="large" placeholder="例如：准备中秋家宴…" aria-label="任务名称输入框" />
        <el-button class="mt-4 w-full h-14 fs-18" type="primary" @click="goChat()">🤖 立即开始拆解</el-button>
      </el-card>
      <!-- 右下：最近总结 + 手动总结 -->
      <el-card>
        <h3 class="fs-22 mb-4">📚 每日学习总结</h3>
        <div v-if="lastSummary" class="c-gray-600 mb-4">最近总结 {{ formatDay(lastSummary.created_at) }}，生成 {{ lastSummary.versions_created }} 个新版本</div>
        <el-popconfirm title="是否立即重新生成今日学习总结？force=true 会覆盖今日已有总结。" confirm-button-text="确认重跑" cancel-button-text="再想想" @confirm="triggerManual()">
          <template #reference>
            <el-button v-if="canTriggerSummary" class="w-full h-14 fs-18" type="warning">⚡ 立即手动总结</el-button>
          </template>
        </el-popconfirm>
      </el-card>
    </section>
  </div>
</template>
```
canTriggerSummary 计算属性：`useUserStore().role in ["OWNER","ADULT"]`

ChatView.vue（全过程）：
核心 stepper 步骤名=中文：①输入任务 → ②智能分类 → ③AI推理策略 → ④家人确认提案 → ⑤已生成任务
SSE 区域：`aria-live="polite"` 滚动消息；家人确认调用 B-1 confirmation POST；取消拆解 A-1 cancel（如用户取消任务本身：调 /tasks/{id}/cancel 原接口）

TemplatesView.vue（三 Tab）：
Tab1 模板列表：分类筛选 el-select（中文化名：家务/买菜/学习…）+ 版本历史 el-collapse；⭐ star is_important 切换用 el-switch + `el-popconfirm`；change_summary 版本变更说明中文化显示（可能多行，用 el-tooltip + 全文）
Tab2 创建模板：大表单 el-form label-position="top" 字号放大；子步骤用 el-table 动态增删；notes 多行 el-input autosize
Tab3 选模板给任务：搜索框；list + 卡片「选择该模板」按钮 → 调 I-6/I-7 pick API

AutomationView.vue：
```
顶部 3 MetricCard（❌3 同 Dashboard）
中部 ❌2 警告：
  <el-alert v-for="w in highCancelWarnings" :key="w.id" type="warning" show-icon closable :title="`⚠️ ${w.name}分类近 7 天被家人连续取消 AI 提案 ${w.count} 次（≥5 触发阈值）。请检查：1. 模板粒度太细太繁？2. 家务规则与家人习惯不符？3. 系统提示词是否需调整？建议：与家人沟通后在「模板页」标记旧版本⭐永久保留，再调整生成新版本。`" />
下部 summary_execution_log el-table：列=日期/trigger_source auto手动/分类数/新版本数/failed_categories（中文红字展示失败原因）/状态（success/partial_success/failed 中文三态）
OWNER/ADULT 右：el-switch "覆盖今日已有总结（force=true）" + ⚡立即总结按钮
```

- [ ] **Step 3: 2 旧视图改造（TasksView Tab2 / InviteView aria）**
TasksView Tab2「AI 拆解」：
```vue
<el-tabs v-model="activeTab">
  <el-tab-pane label="📋 我的任务" name="list">…原代码不动…</el-tab-pane>
  <el-tab-pane label="🤖 AI 拆解" name="ai">
    <el-form label-position="top" class="mt-6 max-w-2xl mx-auto" size="large">
      <el-form-item label="任务名称（必填）">
        <el-input v-model="form.title" placeholder="如：打扫客厅、做午饭…" aria-label="任务名称输入框" />
      </el-form-item>
      <el-form-item label="所属分类">
        <el-select v-model="form.category_id" placeholder="不选则由 AI 智能分类" clearable>…原分类select…</el-select>
      </el-form-item>
      <el-form-item label="给家人的补充备注">
        <el-input v-model="form.notes" type="textarea" :rows="3" placeholder="如：最近有客人来，需重点擦窗台…"></el-input>
      </el-form-item>
      <el-button class="w-full h-14 fs-18" type="primary" @click="startChat()">🤖 立即开始拆解</el-button>
    </el-form>
  </el-tab-pane>
</el-tabs>
```
InviteView：无业务改动，加 aria-label 如「分享卡预览图片」「下载分享卡PNG按钮」「复制邀请码按钮」 + 确保 font store fs-18 fs-20 放大后分享卡 canvas size 同比放大（canvas width×1.25，ctx.scale(1.25)）

- [ ] **Step 4: Run Vitest 7 Views 用例 Expected: PASS**
- [ ] **Step 5: Commit feat(change6): 4新视图Dashboard/Chat/Templates/Automation + Tasks Tab2 AI拆解 + Invite aria+font放大兼容**

---