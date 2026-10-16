// 任务状态机 SSOT（TaskView 桌面网格与 TaskCardMobile 移动卡片共用）
// D3：移动端卡片化时语义色 / 中文标签 / 流转矩阵必须与桌面完全一致

export const STATUS_LABELS = {
  DRAFT: '草稿',
  PENDING: '待办',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  CANCELLED: '已取消'
}

export const STATUS_TAG = {
  DRAFT: 'info',
  PENDING: 'warning',
  IN_PROGRESS: 'primary',
  COMPLETED: 'success',
  CANCELLED: 'danger'
}

export const PRIORITY_LABELS = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高'
}

// 任务状态流转 DFA（与后端 canTransition 守卫一致，前端先拦）
export const DFA = {
  DRAFT: ['PENDING', 'CANCELLED'],
  PENDING: ['IN_PROGRESS', 'COMPLETED', 'CANCELLED'],
  IN_PROGRESS: ['PENDING', 'COMPLETED', 'CANCELLED'],
  COMPLETED: [],
  CANCELLED: []
}

export function statusLabel(s) {
  return STATUS_LABELS[s] || s || ''
}

export function statusTagType(s) {
  return STATUS_TAG[s] || ''
}

export function priorityLabel(p) {
  return PRIORITY_LABELS[p] || p || '中'
}

export function canTransition(from, to) {
  return Array.isArray(DFA[from]) && DFA[from].indexOf(to) !== -1
}
