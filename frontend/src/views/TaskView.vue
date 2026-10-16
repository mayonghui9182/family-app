<template>
  <div class="task-page">
    <!-- 任务列表区 -->
    <section role="region" aria-label="我的任务列表">
      <div class="list-header">
        <h3 class="section-title">📋 全部任务</h3>
        <!-- 创建任务按钮：跳转到独立创建页面，老人更聚焦（不与列表抢焦点） -->
        <el-button type="primary" size="large" class="big-btn" @click="router.push('/tasks/new')">
          ➕ 创建任务
        </el-button>
      </div>

      <!-- 快捷筛选按钮组：全部 / 本人 / 家庭 / 超期 -->
      <div class="filter-bar">
        <el-radio-group v-model="filterScope" size="large" @change="refreshList">
          <el-radio-button label="">🏠 全家</el-radio-button>
          <el-radio-button label="mine">👤 本人</el-radio-button>

        </el-radio-group>
      </div>

      <el-checkbox v-model="filterOverdue" size="large" @change="refreshList">只看逾期未完成</el-checkbox>
      <!-- 状态筛选：保留既有 5 状态切换 -->
      <div class="filter-bar">
        <el-radio-group v-model="filterStatus" size="large" @change="refreshList">
          <el-radio-button label="">不限状态</el-radio-button>
          <el-radio-button label="DRAFT">草稿</el-radio-button>
          <el-radio-button label="PENDING">待办</el-radio-button>
          <el-radio-button label="IN_PROGRESS">进行中</el-radio-button>
          <el-radio-button label="COMPLETED">已完成</el-radio-button>
          <el-radio-button label="CANCELLED">已取消</el-radio-button>
        </el-radio-group>
      </div>

      <div v-if="listError" class="list-error" role="alert">
        <p>任务没加载出来，请检查网络后重试</p>
        <el-button size="large" @click="refreshList">重试一下</el-button>
      </div>
      <div v-else-if="isMobile" v-loading="loadingList" class="task-mobile-list">
        <TaskCardMobile
          v-for="task in tasks"
          :key="task.id"
          :task="task"
          @transition="transition"
          @delete="confirmDelete"
          @edit="editTask"
          @decompose="(t) => router.push('/chat?task_id=' + t.id)"
        />
        <el-empty v-if="!tasks.length && !loadingList" description="还没有任务哦，点上方【创建任务】加一个吧，比如记得早上吃药" aria-label="暂无任务，点上方创建任务添加" />
      </div>
      <div v-else v-loading="loadingList" class="task-grid-wrapper">
        <el-row v-if="tasks.length" :gutter="16" class="task-grid">
          <el-col
            v-for="task in tasks"
            :key="task.id"
            :xs="24"
            :sm="12"
            :md="8"
            :lg="6"
          >
            <el-card class="task-card" :class="['status-' + String(task.status || '').toLowerCase()]" role="article" :aria-label="`任务：${task.title}，状态${statusLabel(task.status)}`">
              <div class="task-card-header">
                <span class="task-title">{{ task.title }}</span>
                <el-tag :type="statusTagType(task.status)" size="large">
                  {{ statusLabel(task.status) }}
                </el-tag>
              </div>
              <div v-if="task.description" class="task-desc">{{ task.description }}</div>
              <div class="task-meta">
                <span v-if="task.dueDate" class="meta-item">📅 截止 {{ task.dueDate }}</span>
                <span v-if="task.priority" class="meta-item">
                  优先级：{{ priorityLabel(task.priority) }}
                </span>
              </div>
              <div class="task-actions">
                <template v-if="task.status === 'DRAFT'">
                  <el-button type="primary" size="large" :aria-label="`开始任务${task.title}，从草稿变为待办`" @click="transition(task, 'PENDING')">
                    ▶️ 开始
                  </el-button>
                  <el-button type="danger" size="large" :aria-label="`取消任务${task.title}`" @click="transition(task, 'CANCELLED')">
                    ❌ 取消
                  </el-button>
                </template>
                <template v-else-if="task.status === 'PENDING'">
                  <el-button type="primary" size="large" :aria-label="`开始做任务${task.title}，从待办变为进行中`" @click="transition(task, 'IN_PROGRESS')">
                    ⚡ 开始做
                  </el-button>
                  <el-button type="success" size="large" :aria-label="`完成任务${task.title}`" @click="transition(task, 'COMPLETED')">
                    ✅ 完成
                  </el-button>
                  <el-button type="danger" size="large" :aria-label="`取消任务${task.title}`" @click="transition(task, 'CANCELLED')">
                    ❌ 取消
                  </el-button>
                </template>
                <template v-else-if="task.status === 'IN_PROGRESS'">
                  <el-button type="success" size="large" :aria-label="`标记任务${task.title}为已完成`" @click="transition(task, 'COMPLETED')">
                    ✅ 已完成
                  </el-button>
                  <el-button size="large" :aria-label="`暂停任务${task.title}，回到待办状态`" @click="transition(task, 'PENDING')">
                    ⏸️ 暂停
                  </el-button>
                  <el-button type="danger" size="large" :aria-label="`取消任务${task.title}`" @click="transition(task, 'CANCELLED')">
                    ❌ 取消
                  </el-button>
                </template>
                <template v-else>
                  <span class="terminal-hint">已结束</span>
                </template>
                <el-button type="info" size="large" :aria-label="`编辑任务${task.title}`" @click="editTask(task)">
                  ✏️ 编辑
                </el-button>
                <el-button size="large" :aria-label="`AI拆解任务${task.title}`" @click="router.push('/chat?task_id=' + task.id)">🤖 AI拆解</el-button>
                <el-button type="danger" size="large" :aria-label="`永久删除任务${task.title}`" @click="confirmDelete(task)">
                  🗑️ 删除任务
                </el-button>
              </div>
            </el-card>
          </el-col>
        </el-row>
        <el-empty v-else-if="!loadingList" description="还没有任务哦，点上方【创建任务】加一个吧，比如记得早上吃药" aria-label="暂无任务，点上方创建任务添加" />
      </div>
      <div v-if="!listError && total > 20" class="filter-bar" aria-label="任务分页">
        <el-button :disabled="loadingList || page === 0" size="large" @click="page--; refreshList(false)">上一页</el-button>
        <span>第 {{ page + 1 }} 页，共 {{ total }} 件事</span>
        <el-button :disabled="loadingList || (page + 1) * 20 >= total" size="large" @click="page++; refreshList(false)">下一页</el-button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, onBeforeUnmount, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { tasksApi } from '../api/tasks'
import { useAuthStore } from '../stores/auth'
import TaskCardMobile from '../components/TaskCardMobile.vue' // D3: 移动端卡片形态
import { useIsMobile } from '../composables/useIsMobile.js'
// 状态中文标签 / 语义色 / 流转 DFA 抽到共享 util，桌面网格与移动卡片同一 SSOT
import { statusLabel, statusTagType, priorityLabel, canTransition } from '../utils/taskStatus.js'

const isMobile = useIsMobile()
const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const tasks = ref([])
const page = ref(0)
const total = ref(0)
const loadingList = ref(false)
// 筛选维度：filterScope（全部/本人/家庭/超期）+ filterStatus（5 状态）
const filterScope = ref('')
const filterStatus = ref('')
const filterOverdue = ref(false)
const listError = ref(false)
let requestVersion = 0
onBeforeUnmount(() => { requestVersion += 1 })

// ===== 监听路由 query：首页统计卡片跳转携带 status/overdue/scope =====
watch(
  () => route.query,
  (q) => {
    filterScope.value = q.scope === 'mine' ? 'mine' : ''
    filterOverdue.value = q.overdue === 'true'
    filterStatus.value = ['DRAFT', 'PENDING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'].includes(q.status)
      ? q.status : ''
    refreshList()
  },
  { immediate: true }
)

// ===== 按当前筛选维度调 list =====
async function refreshList(resetPage = true) {
  if (resetPage !== false) page.value = 0
  const version = ++requestVersion
  loadingList.value = true
  listError.value = false
  try {
    const params = {}
    if (filterStatus.value) params.status = filterStatus.value
    if (filterScope.value === 'mine') {
      // 本人筛选：依赖 authStore.userInfo.userId（登录时存）
      const uid = authStore.userInfo && authStore.userInfo.userId
      if (uid) {
        params.assigneeId = uid
      } else {
        throw new Error('未识别到当前用户，请重新登录')
      }
    }
    if (filterOverdue.value) params.overdue = true
    // family 分支与「全部」一致：不传 assigneeId/overdue
    const data = await tasksApi.page({ ...params, page: page.value, size: 20 })
    if (version === requestVersion) {
      tasks.value = data?.items || []
      total.value = data?.total || 0
    }
  } catch (e) {
    if (version === requestVersion && e?.code !== 'ERR_CANCELED') {
      tasks.value = []
      listError.value = true
    }
  } finally {
    if (version === requestVersion) loadingList.value = false
  }
}

async function transition(task, to) {
  if (!task || !task.id) return
  if (!canTransition(task.status, to)) {
    ElMessage.warning('当前状态不允许此操作')
    return
  }
  if (to === 'CANCELLED') {
    try {
      await ElMessageBox.confirm(
        '确认取消任务「' + (task.title || '') + '」吗？取消后该任务就不再提醒了。',
        '❌ 取消任务确认',
        { confirmButtonText: '确认取消', cancelButtonText: '保留任务', type: 'warning' }
      )
    } catch (e) { return }
  }
  try {
    await tasksApi.updateStatus(task.id, to)
    ElMessage.success('「' + task.title + '」已更新为：' + statusLabel(to))
    await refreshList()
  } catch (e) { /* 拦截器提示 */ }
}

async function confirmDelete(task) {
  try {
    await ElMessageBox.confirm(
      '确定要永久删除任务「' + task.title + '」吗？删除后任务就不再显示啦。',
      '🗑️ 删除任务确认',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '我再想想' }
    )
    await tasksApi.del(task.id)
    ElMessage.success('任务已删除')
    await refreshList()
  } catch (action) { /* cancel 不处理 */ }
}

function editTask(task) {
  router.push('/tasks/' + task.id + '/edit')
}


</script>

<style scoped>
.task-page {
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.list-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 8px;
}
.section-title {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  color: #1a1a1a;
}
.big-btn { min-height: 56px; font-size: 20px; }
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}
.filter-bar :deep(.el-radio-button__inner) {
  min-height: 44px;
  font-size: 16px;
  padding: 0 18px;
}
.task-grid-wrapper { min-height: 200px; }
.task-mobile-list { min-height: 200px; }
.task-card {
  margin-bottom: 16px;
}
.task-card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 8px;
}
.task-title {
  font-size: 18px;
  font-weight: 600;
  color: #1a1a1a;
  flex: 1;
  word-break: break-word;
}
.task-desc {
  font-size: 16px;
  color: #555;
  margin-bottom: 8px;
}
.task-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 14px;
  color: #888;
  margin-bottom: 12px;
}
.task-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.terminal-hint {
  display: inline-flex;
  align-items: center;
  padding: 6px 10px;
  font-size: 14px;
  color: #909399;
  background: #f4f4f5;
  border-radius: 4px;
}
/* 状态色边框（与 TaskCardMobile 共享 SSOT，桌面网格只在卡片层加色边） */
.task-card.status-draft { border-left: 4px solid #909399; }
.task-card.status-pending { border-left: 4px solid #409eff; }
.task-card.status-in_progress { border-left: 4px solid #e6a23c; }
.task-card.status-completed { border-left: 4px solid #67c23a; }
.task-card.status-cancelled { border-left: 4px solid #f56c6c; }
</style>
