/**
 * Pinia store：模板版本管理（C-27~C-31）
 *
 * D8 状态：list[], loading, error
 */
import { defineStore } from 'pinia'
import { templatesApi } from '../api/templates'

export const useTemplatesStore = defineStore('templates', {
  state: () => ({
    list: [],
    versions: [],
    loading: false,
    error: null
  }),
  actions: {
    /** C-27 加载模板列表 */
    async load() {
      this.loading = true
      this.error = null
      try {
        this.list = await templatesApi.list()
      } catch (e) {
        this.error = e.message
      } finally {
        this.loading = false
      }
    },
    /** C-28 加载模板版本历史 */
    async loadVersions(templateId) {
      this.loading = true
      this.error = null
      try {
        const resp = await templatesApi.versions(templateId)
        this.versions = (resp && resp.versions) || []
      } catch (e) {
        this.error = e.message
      } finally {
        this.loading = false
      }
    },
    /** C-29 toggle star */
    async star(versionId) {
      try {
        return await templatesApi.star(versionId)
      } catch (e) {
        this.error = e.message
        throw e
      }
    },
    /** C-30 删除版本 */
    async del(versionId) {
      try {
        await templatesApi.del(versionId)
      } catch (e) {
        this.error = e.message
        throw e
      }
    },
    /** C-31 选模板给任务 */
    async pick(templateId, versionId, taskId) {
      try {
        await templatesApi.pick(templateId, versionId, taskId)
      } catch (e) {
        this.error = e.message
        throw e
      }
    }
  }
})

export default useTemplatesStore
