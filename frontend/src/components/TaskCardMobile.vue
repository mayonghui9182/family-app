<template>
  <el-card
    class="task-card-mobile"
    :class="['status-' + String(task.status || '').toLowerCase()]"
    role="article"
    :aria-label="`任务：${task.title}，状态${statusLabel(task.status)}`"
  >
    <div class="m-card-header">
      <span class="task-title">{{ task.title }}</span>
      <el-tag :type="statusTagType(task.status)" size="large">
        {{ statusLabel(task.status) }}
      </el-tag>
    </div>
    <div v-if="task.description" class="m-desc">{{ task.description }}</div>
    <div class="m-meta">
      <span v-if="assigneeName" class="meta-item">👤 {{ assigneeName }}</span>
      <span v-if="task.dueDate" class="meta-item">📅 截止 {{ task.dueDate }}</span>
      <!-- Change 13：日程来源任务的角标（与 meta 字号一致，不高频不抢眼） -->
      <span v-if="task.source === 'CALENDAR'" class="meta-item">📅 日程</span>
      <span v-if="task.priority" class="meta-item">优先级：{{ priorityLabel(task.priority) }}</span>
    </div>
    <div class="m-actions">
      <template v-if="task.status === 'DRAFT'">
        <el-button type="primary" size="large" :aria-label="`开始任务${task.title}，从草稿变为待办`" @click="emit('transition', task, 'PENDING')">
          ▶️ 开始
        </el-button>
        <el-button type="danger" size="large" :aria-label="`取消任务${task.title}`" @click="emit('transition', task, 'CANCELLED')">
          ❌ 取消
        </el-button>
      </template>
      <template v-else-if="task.status === 'PENDING'">
        <el-button type="primary" size="large" :aria-label="`开始做任务${task.title}，从待办变为进行中`" @click="emit('transition', task, 'IN_PROGRESS')">
          ⚡ 开始做
        </el-button>
        <el-button type="success" size="large" :aria-label="`完成任务${task.title}`" @click="emit('transition', task, 'COMPLETED')">
          ✅ 完成
        </el-button>
        <el-button type="danger" size="large" :aria-label="`取消任务${task.title}`" @click="emit('transition', task, 'CANCELLED')">
          ❌ 取消
        </el-button>
      </template>
      <template v-else-if="task.status === 'IN_PROGRESS'">
        <el-button type="success" size="large" :aria-label="`标记任务${task.title}为已完成`" @click="emit('transition', task, 'COMPLETED')">
          ✅ 已完成
        </el-button>
        <el-button size="large" :aria-label="`暂停任务${task.title}，回到待办状态`" @click="emit('transition', task, 'PENDING')">
          ⏸️ 暂停
        </el-button>
        <el-button type="danger" size="large" :aria-label="`取消任务${task.title}`" @click="emit('transition', task, 'CANCELLED')">
          ❌ 取消
        </el-button>
      </template>
      <template v-else>
        <span class="terminal-hint">已结束</span>
      </template>
      <el-button type="info" size="large" :aria-label="`编辑任务${task.title}`" @click="emit('edit', task)">✏️ 编辑</el-button>
      <el-button size="large" :aria-label="`AI拆解任务${task.title}`" @click="emit('decompose', task)">🤖 AI拆解</el-button>
      <el-button type="danger" size="large" :aria-label="`永久删除任务${task.title}`" @click="emit('delete', task)">
        🗑️ 删除任务
      </el-button>
    </div>
  </el-card>
</template>

<script setup>
// D3: TaskView 移动端卡片形态——数据/store 零改动，仅展示层切换
import { computed } from 'vue'
import { statusLabel, statusTagType, priorityLabel } from '../utils/taskStatus.js'

const props = defineProps({
  task: { type: Object, required: true }
})

const emit = defineEmits(['transition', 'delete', 'decompose', 'edit'])

// 新列 assigneeProfile 优先，旧字段兜底
const assigneeName = computed(() => {
  return props.task?.assigneeProfile?.name || props.task?.assigneeName || ''
})
</script>

<style scoped>
.task-card-mobile {
  margin-bottom: 12px;
}
.m-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.task-title {
  font-size: 18px;
  font-weight: 700;
  color: #1f2937;
}
.m-desc {
  margin-top: 8px;
  font-size: 15px;
  color: #606266;
}
.m-meta {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 14px;
  color: #606266;
}
.m-actions {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.m-actions .el-button {
  margin-left: 0;
  width: 100%;
}
/* 状态操作按钮保持整行大按钮（老人主操作），
   末尾两个次要按钮（AI拆解/删除）并排半宽，缩短卡片高度 */
.m-actions .el-button:nth-last-child(2),
.m-actions .el-button:last-child {
  width: calc(50% - 4px);
}
.terminal-hint {
  font-size: 15px;
  font-weight: 600;
  color: #909399;
  padding: 8px 0;
}
.task-card-mobile.status-draft { border-top: 3px solid #909399; }
.task-card-mobile.status-pending { border-top: 3px solid #d97706; }
.task-card-mobile.status-in_progress { border-top: 3px solid #2563eb; }
.task-card-mobile.status-completed { border-top: 3px solid #16a34a; }
.task-card-mobile.status-cancelled { border-top: 3px solid #dc2626; }
</style>
