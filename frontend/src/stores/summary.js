/**
 * Pinia store：总结管理（C-32~C-34）
 *
 * D8 状态：triggerPending/null, lastSummary, metrics, isLoading
 */
import { defineStore } from 'pinia'
import { summaryApi } from '../api/sdkSummary'

export const useSummaryStore = defineStore('summary', {
  state: () => ({
    triggerPending: false,
    lastSummary: null,
    list: [],
    metrics: null,
    loading: false,
    error: null
  }),
  actions: {
    /** C-32 手动触发总结 */
    async trigger(familyId) {
      this.triggerPending = true
      this.error = null
      try {
        this.lastSummary = await summaryApi.trigger(familyId)
      } catch (e) {
        this.error = e.message
        throw e
      } finally {
        this.triggerPending = false
      }
    },
    /** C-33 加载总结日志列表 */
    async loadList(familyId) {
      this.loading = true
      this.error = null
      try {
        this.list = await summaryApi.list(familyId)
      } catch (e) {
        this.error = e.message
      } finally {
        this.loading = false
      }
    },
    /** C-34 加载 3 指标聚合 */
    async loadMetrics(familyId) {
      this.loading = true
      this.error = null
      try {
        this.metrics = await summaryApi.metrics(familyId)
      } catch (e) {
        this.error = e.message
      } finally {
        this.loading = false
      }
    }
  }
})

export default useSummaryStore
