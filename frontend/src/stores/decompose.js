import { defineStore } from 'pinia'
import client from '../api/client'

/**
 * AI 拆解功能开关 store（Change 7 Task 2 子步骤 6）。
 * 初始化阶段调 /api/agent/v1/config（D-7 公开端点，无需 token/family_id），
 * 当 ai_decompose_enabled=false 时给 document.body 加 'ai-disabled' class，
 * 由全局 CSS 隐藏所有 AI 入口按钮（.ai-entry-btn / .ai-decompose-card / [data-ai="true"]）。
 * 调用失败时保守视为关闭，避免老人误点 AI 按钮后看到报错。
 */
export const useDecomposeStore = defineStore('decompose', {
  state: () => ({
    // null = 未加载；true/false = 已从 /config 读到开关状态
    aiDecomposeEnabled: null,
    loaded: false
  }),
  getters: {
    // 是否已明确关闭（前端用它决定是否渲染 AI 入口）
    disabled: (state) => state.loaded && state.aiDecomposeEnabled === false
  },
  actions: {
    // 应用启动时调用：读 /config 并同步 body class
    async init() {
      try {
        const data = await client.get('/agent/v1/config')
        // client 响应拦截器已剥外壳，data 即 { ai_decompose_enabled: bool }
        this.aiDecomposeEnabled = !!(data && data.ai_decompose_enabled)
      } catch (e) {
        // /config 调用失败：保守视为关闭（隐藏 AI 入口，避免老人误触报错）
        this.aiDecomposeEnabled = false
      }
      this.loaded = true
      this.applyBodyClass()
    },
    // 同步 document.body 的 'ai-disabled' class（SSR 守卫，浏览器环境直接操作 body）
    applyBodyClass() {
      if (typeof document === 'undefined') return
      if (this.aiDecomposeEnabled === false) {
        document.body.classList.add('ai-disabled')
      } else {
        document.body.classList.remove('ai-disabled')
      }
    }
  }
})
