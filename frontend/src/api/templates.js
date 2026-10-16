/**
 * C-27~C-31 模板版本管理 SDK
 *
 * C-27 GET    /agent/v1/templates — 模板列表（家庭隔离）
 * C-28 GET    /agent/v1/templates/{id}/versions — 版本历史
 * C-29 PATCH  /agent/v1/templates/versions/{v}/star — toggle is_important
 * C-30 DELETE /agent/v1/templates/versions/{v} — 删除版本
 * C-31 POST   /agent/v1/templates/{tplId}/versions/{v}/pick — 选模板给任务
 */
import client from './client'

export const templatesApi = {
  /** C-27 模板列表 */
  list: () =>
    client.get('/agent/v1/templates'),

  /** C-28 模板版本历史 */
  versions: (templateId) =>
    client.get(`/agent/v1/templates/${templateId}/versions`),

  /** C-29 toggle star（is_important） */
  star: (versionId) =>
    client.patch(`/agent/v1/templates/versions/${versionId}/star`),

  /** C-30 删除版本 */
  del: (versionId) =>
    client.delete(`/agent/v1/templates/versions/${versionId}`),

  /** C-31 选模板给任务 */
  pick: (templateId, versionId, taskId) =>
    client.post(
      `/agent/v1/templates/${templateId}/versions/${versionId}/pick`,
      { task_id: taskId }
    )
}

export default templatesApi
