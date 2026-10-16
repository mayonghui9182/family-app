import client from './client'

/**
 * 任务相关 API。
 * list 支持完整查询参数：status/categoryId/source/keyword/dueBefore（既有）
 * + assigneeId/overdue（首页统计卡片跳转与「我的任务」筛选新增）。
 */
export const tasksApi = {
  page:         (params = {})   => client.get('/tasks/page', { params }),
  list:         (params = {})   => client.get('/tasks', { params }),
  create:       (payload)       => client.post('/tasks', payload),
  get:          (id)            => client.get(`/tasks/${id}`),
  update:       (id, payload)   => client.put(`/tasks/${id}`, payload),
  del:          (id)            => client.delete(`/tasks/${id}`),
  updateStatus: (id, status)    => client.post(`/tasks/${id}/status`, { status }),
  /** 首页统计：4 个数字（家庭待办/本人待办/家庭逾期/本人逾期） */
  stats:        ()              => client.get('/tasks/stats')
}

export default tasksApi
