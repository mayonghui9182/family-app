import client from './client'

/**
 * 日程 API（Change 13）：对接后端 CalendarController E-1~E-6。
 * baseURL '/api' 由 client.js 注入，故此处路径均以 /calendar 开头。
 * 响应已经过 client 拦截器解包（code!==0 时统一中文报错并 reject），
 * 这里拿到的直接是业务数据（Result.data）。
 */
export const calendarApi = {
  // E-1 日程列表；includeInactive=true 时一并返回已停用日程（查询参数仅此场景携带）
  list:   (includeInactive) => includeInactive
    ? client.get('/calendar/events', { params: { includeInactive: true } })
    : client.get('/calendar/events'),
  // E-2 新建日程；payload.recurrence 为 JSON 字符串（如 {"freq":"weekly","weekdays":[2]}），与一次性日期二选一
  create: (payload)         => client.post('/calendar/events', payload),
  // E-3 日程详情
  get:    (id)              => client.get(`/calendar/events/${id}`),
  // E-4 更新日程（PATCH 语义：仅非空字段被更新；recurrence 传空串与不传等价）
  update: (id, payload)     => client.put(`/calendar/events/${id}`, payload),
  // E-5 删除日程（后端为软删）
  del:    (id)              => client.delete(`/calendar/events/${id}`),
  // E-6 今日日程展开：返回 [{ eventId, title, eventTime, assigneeProfileId }]，按时间升序
  today:  ()                => client.get('/calendar/today')
}

export default calendarApi
