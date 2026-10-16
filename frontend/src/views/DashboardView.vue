<template>
  <div class="dashboard-view" aria-live="polite">
    <h1 class="page-title">🏠 今天的安排</h1>
    <p class="page-subtitle">看看今天要做什么，也可以随手记下一件事。</p>

    <!-- 顶部快捷入口按钮（不用卡片，紧贴欢迎语） -->
    <div class="quick-actions">
      <el-button size="large" class="big-btn" @click="$router.push('/daily')">☀️ 我的生活与工作计划</el-button>
      <el-button type="primary" size="large" class="big-btn" @click="$router.push('/tasks/new')">
        ➕ 记一件事
      </el-button>
      <el-button type="success" size="large" class="big-btn" @click="$router.push('/calendar')">
        📅 查看日程
      </el-button>
    </div>

    <!-- 4 张任务统计卡片：家庭待办/本人待办/家庭逾期/本人逾期，点击跳转任务列表 -->
    <el-row :gutter="16" class="stats-row">
      <el-col :xs="12" :sm="6">
        <div class="stat-card stat-pending" role="button" tabindex="0"
             :aria-label="`家庭待办 ${stats?.familyPendingCount ?? '--'} 件，点击查看`"
             @click="goTasks({ status: 'PENDING' })" @keyup.enter="goTasks({ status: 'PENDING' })">
          <div class="stat-emoji">📋</div>
          <div class="stat-num">{{ stats?.familyPendingCount ?? '--' }}</div>
          <div class="stat-label">家庭待办</div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card stat-mine-pending" role="button" tabindex="0"
             :aria-label="`本人待办 ${stats?.minePendingCount ?? '--'} 件，点击查看`"
             @click="goTasks({ status: 'PENDING', scope: 'mine' })" @keyup.enter="goTasks({ status: 'PENDING', scope: 'mine' })">
          <div class="stat-emoji">🙋</div>
          <div class="stat-num">{{ stats?.minePendingCount ?? '--' }}</div>
          <div class="stat-label">本人待办</div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card stat-overdue" role="button" tabindex="0"
             :aria-label="`家庭逾期 ${stats?.familyOverdueCount ?? '--'} 件，点击查看`"
             @click="goTasks({ overdue: 'true' })" @keyup.enter="goTasks({ overdue: 'true' })">
          <div class="stat-emoji">⏰</div>
          <div class="stat-num">{{ stats?.familyOverdueCount ?? '--' }}</div>
          <div class="stat-label">家庭逾期</div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card stat-mine-overdue" role="button" tabindex="0"
             :aria-label="`本人逾期 ${stats?.mineOverdueCount ?? '--'} 件，点击查看`"
             @click="goTasks({ overdue: 'true', scope: 'mine' })" @keyup.enter="goTasks({ overdue: 'true', scope: 'mine' })">
          <div class="stat-emoji">⚠️</div>
          <div class="stat-num">{{ stats?.mineOverdueCount ?? '--' }}</div>
          <div class="stat-label">本人逾期</div>
        </div>
      </el-col>
    </el-row>

    <!-- 今日日程（Change 13）：来自 E-6 展开结果。
         失败态必须与空态分开：接口没拉到 ≠ 今天真的没事，渲染成同一句话等于对老人撒谎（终审 IMP-3） -->
    <el-card class="section-card">
      <template #header><span class="card-title">📅 今日日程</span></template>
      <div v-if="eventsError" class="event-error card-desc">
        <span>日程没加载出来，请检查网络后重试</span>
        <el-button type="warning" size="large" class="big-btn" @click="loadTodayEvents">
          🔄 重试一下
        </el-button>
      </div>
      <div v-else-if="eventsLoading" class="card-desc" role="status">正在加载日程…</div>
      <div v-else-if="todayEvents.length === 0" class="card-desc">今天没有日程安排</div>
      <div v-else class="event-list">
        <div v-for="ev in todayEvents" :key="ev.eventId" class="event-item">
          <span class="event-time">{{ ev.eventTime }}</span>
          <span class="event-title">{{ ev.title }}</span>
        </div>
      </div>
    </el-card>

    <!-- 中部最近任务表：失败态与空态必须分开（终审挂账，与上方今日日程 IMP-3 同类谎报） -->
    <el-card class="section-card">
      <template #header><span class="card-title">📝 最近任务</span></template>
      <div v-if="tasksError" class="task-error card-desc">
        <span>任务没加载出来，请检查网络后重试</span>
        <el-button type="warning" size="large" class="big-btn" @click="loadRecentTasks">
          🔄 重试一下
        </el-button>
      </div>
      <p v-else-if="tasksLoading" role="status">正在加载任务…</p>
      <el-table v-else :data="recentTasks" :max-height="300" size="large" empty-text="暂无任务">
        <el-table-column prop="title" label="任务" min-width="200" />
        <el-table-column label="状态" width="100"><template #default="{ row }">{{ statusLabel(row.status) }}</template></el-table-column>
        <el-table-column label="优先级" width="100"><template #default="{ row }">{{ priorityLabel(row.priority) }}</template></el-table-column>
      </el-table>
    </el-card>

    <!-- 底部 AI 指标：列表形式不用卡片，3 项一行 -->
    <details class="metrics-list">
      <summary>了解 AI 助手的使用情况</summary>
      <div class="metrics-list-title">📊 AI 助手近 30 天指标</div>
      <ul class="metrics-items">
        <li>
          <span class="metric-name">🎯 AI 拆解成功率</span>
          <span class="metric-value">{{ metrics ? metrics.success_rate_30d : '--' }}%</span>
        </li>
        <li>
          <span class="metric-name">📋 模板版本数</span>
          <span class="metric-value">{{ metrics ? metrics.version_count_30d : '--' }} 个</span>
        </li>
        <li>
          <span class="metric-name">🧠 学习调整次数</span>
          <span class="metric-value">{{ metrics ? metrics.learned_adjustments_30d : '--' }} 次</span>
        </li>
      </ul>
    </details>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { statusLabel, priorityLabel } from '../utils/taskStatus'
import { useRouter } from 'vue-router'
import { useSummaryStore } from '@/stores/summary'
import { tasksApi } from '@/api/tasks'
import { calendarApi } from '@/api/calendar'

const router = useRouter()
const summaryStore = useSummaryStore()
const metrics = ref(null)
const stats = ref(null)
const recentTasks = ref([])
// 最近任务是否加载失败：与 eventsError 同理，「没拉到」不能渲染成「暂无任务」
const tasksError = ref(false)
const todayEvents = ref([])
// 今日日程是否加载失败：单独占一个状态，避免与「今天确实没有日程」共用同一空态（终审 IMP-3）
const eventsError = ref(false)
const eventsLoading = ref(true)
const tasksLoading = ref(true)

/** 跳转任务列表，支持筛选参数：status / overdue / scope=mine。 */
function goTasks({ status, overdue, scope } = {}) {
  const query = {}
  if (status) query.status = status
  if (overdue) query.overdue = overdue
  if (scope === 'mine') query.scope = 'mine'
  router.push({ path: '/tasks', query })
}

/** 拉取今日日程（E-6）。失败提示由 api/client.js 响应拦截器统一弹一次，这里不再重复报错。 */
async function loadTodayEvents() {
  eventsError.value = false
  eventsLoading.value = true
  try {
    todayEvents.value = (await calendarApi.today()) || []
  } catch (e) {
    // 吞掉异常只为不阻塞首页其余区块；同时切到可重试的失败态。
    // 刻意不降级成「今天没有日程安排」——那会把「没拉到」说成「没事」，老人会因此漏掉吃药/接人。
    todayEvents.value = []
    eventsError.value = true
  } finally {
    eventsLoading.value = false
  }
}

/** 拉取最近任务。失败提示同样由 client.js 拦截器统一弹一次，这里只切换可重试的失败态。 */
async function loadRecentTasks() {
  tasksError.value = false
  tasksLoading.value = true
  try {
    recentTasks.value = (await tasksApi.page({ page: 0, size: 5 }))?.items || []
  } catch (e) {
    // 与今日日程同一判据：失败时清空列表并显式报错，绝不落到 el-table 的「暂无任务」空态上骗人。
    recentTasks.value = []
    tasksError.value = true
  } finally {
    tasksLoading.value = false
  }
}

/** 拉取首页任务统计（4 个数字）。失败时 stats 保持 null，卡片显示占位 '--'。 */
async function loadStats() {
  try {
    stats.value = await tasksApi.stats()
  } catch (e) {
    // 统计失败不影响首页其余区块；stats=null 时卡片显示 '--'，不报错不阻塞
    stats.value = null
  }
}

async function loadMetrics() {
  const fid = localStorage.getItem('currentFamilyId')
  if (fid) {
    try {
      // 必须 await：原写法把 loadMetrics(...).then(...) 裸挂，外层 try/catch 捕不到异步 reject（死代码），
      // 且首页其余区块会在指标未就绪时就完成渲染
      await summaryStore.loadMetrics(fid)
      metrics.value = summaryStore.metrics
    } catch (e) {
      // store 内部已记 this.error，这里兜住未来的 rethrow：指标保持未就绪，
      // 页面显示占位 '--'，既不谎报数值也不显示 NaN
      metrics.value = null
    }
  }
}

onMounted(() => Promise.all([loadRecentTasks(), loadTodayEvents(), loadStats(), loadMetrics()]))
</script>

<style scoped>
.dashboard-view { padding: 24px 32px; font-family: system-ui, "PingFang SC", "Microsoft YaHei", sans-serif; }
.page-title { font-size: 36px; font-weight: 700; margin: 0 0 8px 0; color: #1a1a1a; }
.page-subtitle { font-size: 20px; color: #555; margin: 0 0 16px 0; }

/* 顶部快捷入口：横向排列，间距宽松，老人易点 */
.quick-actions {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 24px;
}
.quick-actions .big-btn { min-width: 160px; }

/* 4 张统计卡片：高对比度、大数字、点击区域大 */
.stats-row { margin-bottom: 24px; }
.stats-row :deep(.el-col) { margin-bottom: 12px; }
.stats-row :deep(.el-col:last-child) { margin-bottom: 0; }
.stat-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 130px;
  padding: 16px 8px;
  background: #fff;
  border: 1px solid #ebeef5;
  border-left-width: 6px;
  border-radius: 12px;
  cursor: pointer;
  transition: box-shadow 0.15s ease, transform 0.05s ease;
  text-decoration: none;
  color: inherit;
}
.stat-card:hover { box-shadow: 0 4px 14px rgba(37, 99, 235, 0.18); }
.stat-card:active { transform: translateY(1px); }
.stat-card:focus-visible { outline: 3px solid #2563eb; outline-offset: 2px; }
.stat-emoji { font-size: 28px; line-height: 1; }
.stat-num { font-size: 36px; font-weight: 700; color: #1a1a1a; line-height: 1.1; }
.stat-label { font-size: 16px; color: #555; }
.stat-pending { border-left-color: #2563eb; }
.stat-mine-pending { border-left-color: #0891b2; }
.stat-overdue { border-left-color: #dc2626; }
.stat-mine-overdue { border-left-color: #ea580c; }
.stat-overdue .stat-num,
.stat-mine-overdue .stat-num { color: #b91c1c; }

.section-card { margin-bottom: 16px; }
.card-title { font-size: 20px; font-weight: 600; }
.card-desc { font-size: 18px; color: #666; margin: 12px 0; }
.big-btn { min-height: 56px; font-size: 20px; }
/* 今日日程条目：字号与卡片正文一致（硬约束 2：老人易用） */
.event-list { display: flex; flex-direction: column; }
.event-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 10px 4px;
  border-bottom: 1px solid #f0f2f5;
}
.event-item:last-child { border-bottom: none; }
.event-time { font-size: 18px; font-weight: 700; color: #b3541e; min-width: 72px; }
.event-title { font-size: 18px; color: #1a1a1a; }
/* 加载失败态（今日日程与最近任务同形复用）：文案与重试按钮竖排，按钮保持大点击区（硬约束 2：报错必须给下一步） */
.event-error,
.task-error { display: flex; flex-direction: column; align-items: flex-start; gap: 12px; }

/* 底部 AI 指标列表：不用卡片，朴素 list 语义 */
.metrics-list {
  margin-top: 24px;
  padding: 16px 20px;
  background: #f8fafc;
  border-radius: 8px;
}
.metrics-list-title { font-size: 18px; font-weight: 600; color: #1f2937; margin-bottom: 8px; }
.metrics-items { list-style: none; margin: 0; padding: 0; }
.metrics-items li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid #e5e7eb;
  font-size: 16px;
}
.metrics-items li:last-child { border-bottom: none; }
.metric-name { color: #4b5563; }
.metric-value { font-weight: 600; color: #1f2937; }

/* 移动端：外层 AppLayout 已给 12px 内边距，这里归零避免双重挤压；标题缩小 */
@media (max-width: 768px) {
  .dashboard-view { padding: 0; }
  .page-title { font-size: 26px; }
  .page-subtitle { font-size: 16px; margin-bottom: 12px; }
  /* 长入口独占首行，其余入口两列；取消组件默认左外边距，避免窄屏挤压。 */
  .quick-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
    margin-bottom: 16px;
  }
  .quick-actions .big-btn {
    min-width: 0;
    width: 100%;
    height: auto;
    margin: 0;
    padding: 12px 10px;
    white-space: normal;
    line-height: 1.5;
  }
  .quick-actions .big-btn:first-child { grid-column: 1 / -1; }
  /* 按钮内层文字也允许换行，兼容微信和系统大字号。 */
  .quick-actions .big-btn :deep(span) {
    min-width: 0;
    white-space: normal;
    overflow-wrap: anywhere;
  }
  .stats-row { margin-bottom: 16px; }
  .stat-num { font-size: 28px; }
  .stat-label { font-size: 14px; }
}
/* 极窄屏下全部入口单列，保留大字号并避免短按钮被拆成两行。 */
@media (max-width: 360px) {
  .quick-actions { grid-template-columns: minmax(0, 1fr); }
}
</style>
