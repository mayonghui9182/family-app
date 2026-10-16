<template>
  <div class="decompose-chat-page">
    <!-- ===== 页面级导航栏：返回任务列表 + 页面标题（老人一眼知道在哪里） ===== -->
    <div class="dcp-nav">
      <el-button size="large" @click="goBack">{{ isInterview ? '🔙 返回人员档案' : '🔙 返回任务列表' }}</el-button>
      <h2 class="dcp-nav-title">
        <span class="dcp-nav-emoji">{{ isInterview ? '🧠' : '🤖' }}</span>
        {{ isInterview ? 'AI 完善档案访谈' : '帮我安排步骤' }}
      </h2>
      <div style="min-width: 130px;"></div> <!-- 占位，flex 左右两端对齐，title 居中 -->
    </div>

    <!-- ===== URL 查询参数上下文提示（有值时显示） ===== -->
    <el-alert
      v-if="hasQueryContext"
      type="info"
      show-icon
      :closable="false"
      class="dcp-query-alert"
    >
      <template #title>
        <div style="font-size: 16px;">
          {{ isInterview ? '🧠 正在访谈完善档案：' : '📌 正在安排：' }}
          <strong v-if="queryContext.title">{{ isInterview ? queryContext.title : `标题「${queryContext.title}」` }}</strong>
          <span v-if="queryContext.description"> · 描述：{{ queryContext.description }}</span>

        </div>
      </template>
    </el-alert>

    <div v-if="resumeId" class="dcp-wrapper-inner">
      <p>你还有一份保存过的安排。</p>
      <el-button size="large" type="primary" @click="resume">继续上次安排</el-button>
      <el-button size="large" @click="startNew">开始新的安排</el-button>
    </div>
    <p v-if="restoreError" role="alert">{{ restoreError }}</p>
    <!-- ===== 核心面板：始终可见（不再 v-if，页面级永久挂载进入就 idle 欢迎态） ===== -->
    <div class="dcp-wrapper-inner">
      <DecomposeChatPanel
        v-if="!resumeId && !restoreError"
        v-model:visible="panelVisible"
        :init-form="pageInitForm"
        @confirmed="handleConfirmed"
        @archived="handleFinal"
        @cancelled="handleFinal"
      />
    </div>
  </div>
</template>

<script setup>
/**
 * DecomposeChatPageWrapper.vue（T1-10 S6 前半）
 *
 * 用途：/chat 路由独立页面（替代旧 Change6 ChatView.vue 一次性状态机）
 *        S4 用户点 AppSider 侧边栏 "AI 拆解" 菜单 / 外部 deep-link 直接访问 /chat
 *
 * URL query 支持：
 *   /chat?title=xxx&description=yyy → 传入 initForm.title / initForm.description
 *   /chat?task_id=12345            → （未来 S6 联调真链路时）接入任务详情；当前仅作为上下文显示
 *
 * 组件行为：
 *   · 导航栏始终显示「🔙 返回任务列表」按钮
 *   · URL 参数自动提取、显示在顶部 info alert，作为 initForm 传入 DecomposeChatPanel
 *   · 面板 visible 始终 = true（页面级，不用开合；用户"关闭"时返回 /tasks 而不是隐藏）
 */
import { ref, computed, onMounted, watch } from 'vue'
import { tasksApi } from '../api/tasks'
import { useRoute, useRouter } from 'vue-router'
import DecomposeChatPanel from '../components/DecomposeChatPanel.vue'
import { useDecomposeSseStore } from '../stores/decomposeSse'

const route = useRoute()
const router = useRouter()
const decomposeStore = useDecomposeSseStore()

// ===== 面板显隐：页面级始终 visible（update:visible=false 时视为"返回任务列表"） =====
const panelVisible = ref(true)

// ===== 进入页面时强制重置 store 到 idle 态（修复"首次进入无输入框"）
// 根因：用户在 /tasks 触发 AI 拆解后 store.status 可能残留 chatting/proposing，
// 直接点导航到 /chat 时 DecomposeChatPanel 渲染非 idle 态，看不到 idle 输入框。
// 在 onMounted 同步重置确保进入 /chat 一定是 idle 欢迎态。
const taskContext = ref(null)
const resumeId = ref(null)
const restoreError = ref('')
const resumeKey = `family-chat:${localStorage.getItem('currentFamilyId') || ''}`
onMounted(async () => {
  const taskId = route.query.task_id
  if (taskId) {
    try { taskContext.value = await tasksApi.get(taskId) }
    catch { restoreError.value = '这件事未读取成功，请返回任务列表重试。' }
  } else if (!decomposeStore.sessionId && String(route.query?.session_type || '') !== 'PROFILE_INTERVIEW') {
    // 访谈 deep-link 不弹「继续上次安排」：档案页进来就是要开始新访谈
    resumeId.value = sessionStorage.getItem(resumeKey)
  }
})
watch(() => decomposeStore.sessionId, id => {
  if (id) sessionStorage.setItem(resumeKey, String(id))
  else sessionStorage.removeItem(resumeKey)
})
async function resume() {
  try { await decomposeStore.restoreSession(resumeId.value); resumeId.value = null }
  catch { restoreError.value = '上次安排未读取成功，请重试或开始新的安排。' }
}
function startNew() {
  decomposeStore.resetAllClean(true)
  sessionStorage.removeItem(resumeKey)
  resumeId.value = null
  restoreError.value = ''
}

// ===== URL query 提取：title / description / task_id → 中文 key 名对齐 form 规范 =====
const queryContext = computed(() => ({
  title: String(route.query?.title || ''),
  description: String(route.query?.description || ''),
  taskId: route.query?.task_id ? String(route.query.task_id) : null
}))
const hasQueryContext = computed(() => {
  const q = queryContext.value
  return q.title || q.description || q.taskId
})

// ===== 传入面板的 initForm：title/description/dueDate/categoryId/assigneeProfileId/sessionType（priority 默认 MEDIUM） =====
// sessionType：档案页 AI 访谈 deep-link 带 ?session_type=PROFILE_INTERVIEW（其余场景一律 TASK_CREATE）
const pageInitForm = computed(() => ({
  title: taskContext.value?.title || queryContext.value.title,
  description: taskContext.value?.description || queryContext.value.description,
  dueDate: String(route.query?.dueDate || ''),
  priority: String(route.query?.priority || 'MEDIUM'),
  categoryId: route.query?.categoryId != null ? route.query.categoryId : null,
  assigneeProfileId: route.query?.assigneeProfileId != null ? route.query.assigneeProfileId : null,
  sessionType: String(route.query?.session_type || 'TASK_CREATE')
}))
const isInterview = computed(() => pageInitForm.value.sessionType === 'PROFILE_INTERVIEW')

// ===== 返回：访谈模式回档案页，其余回任务列表 =====
function goBack() {
  router.push({ path: isInterview.value ? '/profiles' : '/tasks' })
}

// ===== 终态回调 =====
function handleConfirmed(/* result */) {
  // 用户在页面级确认生成任务 → 直接跳转任务列表让用户立即查看
  goBack()
}
function handleFinal() {
  // archived 或 cancelled 时也跳转回任务列表（避免停在空态页面，老人更自然）
  goBack()
}
</script>

<style scoped>
.decompose-chat-page {
  min-height: 100vh;
  background: #f8fafc;
  padding: 0 0 40px;
}
.dcp-nav {
  display: flex; justify-content: space-between; align-items: center;
  padding: 16px 32px;
  background: linear-gradient(135deg, #eef2ff, #e0e7ff);
  border-bottom: 1px solid #d1d5db;
  margin-bottom: 24px;
}
.dcp-nav-title {
  margin: 0; font-size: 24px; font-weight: 700; color: #1e1b4b;
  display: flex; align-items: center; gap: 10px;
}
.dcp-nav-emoji { font-size: 32px; }
/* 移动端：导航栏改为垂直排列，避免标题被挤成竖排 */
@media (max-width: 768px) {
  .dcp-nav {
    flex-direction: column;
    gap: 12px;
    padding: 12px 16px;
  }
  .dcp-nav-title {
    font-size: 20px;
    text-align: center;
  }
  .dcp-nav-emoji { font-size: 26px; }
  .dcp-nav > div[style] { display: none; }
}
.dcp-query-alert {
  max-width: 1100px; margin: 0 auto 20px;
}
.dcp-wrapper-inner {
  max-width: 1180px; margin: 0 auto;
  padding: 0 24px;
}
</style>
