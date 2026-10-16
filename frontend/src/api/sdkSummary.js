/**
 * C-32~C-34 总结管理 SDK
 *
 * C-32 GET /agent/v1/summary/trigger/{familyId} — 手动触发总结
 * C-33 GET /agent/v1/summary/list/{familyId} — 总结日志列表
 * C-34 GET /agent/v1/summary/metrics/{familyId} — 3 指标聚合
 */
import client from './client'

export const summaryApi = {
  /** C-32 手动触发总结（CHILD 角色会被后端 40302 拦截） */
  trigger: (familyId) =>
    client.get(`/agent/v1/summary/trigger/${familyId}`),

  /** C-33 总结日志列表（倒序，limit 20） */
  list: (familyId) =>
    client.get(`/agent/v1/summary/list/${familyId}`),

  /** C-34 3 指标聚合（success_rate_30d / version_count_30d / learned_adjustments_30d） */
  metrics: (familyId) =>
    client.get(`/agent/v1/summary/metrics/${familyId}`)
}

export default summaryApi
