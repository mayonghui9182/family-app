/**
 * Pinia store：AI 拆解 SSE 会话型 6 态状态机（T1-10 S5 Step1 重写）
 *
 * ⚠️ 保持导出签名不变：export const useDecomposeSseStore = defineStore(...) + export default
 *    （旧 Change6 引用不炸；/chat 路由 Wrapper S6 仍能 useDecomposeSseStore）
 *
 * 6 态状态机（plans §3 full reset 防串台）：
 *   idle  → chatting (用户发消息 / 一键演示)  → proposing (点"生成拆解"/第3轮demo)  → confirmed (✅ D-5)
 *   proposing                            → archived  (✅ D-6 只存档案)
 *   chatting / proposing                → cancelled (❌ D-7 放弃)
 *
 * 约束 8：SSE 事件 1 套 onSseEvent 统一分发（typing/message/patch_preview/reasoning/proposal/error/done），
 *         不写 if(MOCK)…else… 分支。
 * 约束 9：_demoTimers 链式可 Abort（reset/cancel/demo入口 3 处一律 _clearDemoTimers()）。
 */
import { defineStore } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getSession,
  createSession,       // D-1 真（POST /api/agent/v1/decompose/sessions）
  confirm,             // D-5 真（POST /{id}/confirm）
  saveProfileOnly,     // D-6 真（POST /{id}/save-profile-only）
  cancel as cancelApi  // D-7 真（DELETE /{id}）
} from '../api/decomposeSession.js'
import client from '../api/client.js'

// ===== 模块级闭包：非响应式 Abort 控制（Pinia state 不存函数，保持 cancel 引用可用）=====
let _sseController = null // {cancel():void} — 当前 chatStreamMock/decomposeStreamMock 返回
let _streamVersion = 0
let _initVersion = 0
let _demoTimers = []      // 一键演示 setTimeout id 集合

function _clearDemoTimers() {
  _demoTimers.forEach(id => clearTimeout(id))
  _demoTimers = []
}
function _abortSse() {
  _streamVersion += 1
  if (_sseController && typeof _sseController.cancel === 'function') {
    try { _sseController.cancel() } catch {}
  }
  _sseController = null
}
function _genMockSessionId() {
  return 'mock-session-' + Date.now() + '-' + Math.floor(Math.random() * 1000)
}

/**
 * 真实 SSE 流：POST /api/agent/v1/decompose/start
 * agent-service 返回 reasoning → proposal → done 事件
 * 返回 controller{cancel()} 供 Abort
 */
function _realSseStream(message, sessionId, { onEvent, onError, onClose } = {}) {
  const controller = { _cancelled: false, _abortCtrl: null }

  controller.cancel = function() {
    this._cancelled = true
    if (this._abortCtrl) {
      try { this._abortCtrl.abort() } catch {}
    }
  }

  controller._abortCtrl = new AbortController()

  ;(async () => {
    try {
      const token = localStorage.getItem('accessToken')
      const familyId = localStorage.getItem('currentFamilyId')
      const headers = { 'Content-Type': 'application/json' }
      if (token) headers['X-Access-Token'] = token
      if (familyId) headers['X-Family-Id'] = familyId

      const resp = await fetch('/api/agent/v1/decompose/start', {
        method: 'POST',
        headers,
        body: JSON.stringify({ message, session_id: sessionId }),
        signal: controller._abortCtrl.signal
      })

      if (!resp.ok) {
        const text = await resp.text().catch(() => '')
        throw new Error(`HTTP ${resp.status}: ${text}`)
      }

      const reader = resp.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let currentEvent = ''
      let currentData = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        if (controller._cancelled) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() // 保留不完整的行

        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            currentData += (currentData ? '\n' : '') + line.slice(5).trim()
          } else if (line.trim() === '' && currentEvent) {
            // 空行 = 事件结束
            if (onEvent) onEvent(currentEvent, currentData)
            currentEvent = ''
            currentData = ''
          }
        }
      }
      // 处理最后一个事件
      if (currentEvent && currentData && !controller._cancelled) {
        if (onEvent) onEvent(currentEvent, currentData)
      }
      if (onClose && !controller._cancelled) onClose()
    } catch (e) {
      if (controller._cancelled) return
      if (e.name === 'AbortError') return
      if (onError) onError(e)
    }
  })()

  return controller
}

// ===== 状态机 store（保持 Pinia defineStore 形式；组件内 useXxxStore() 调用，非顶层调用）=====
export const useDecomposeSseStore = defineStore('decomposeSse', {
  state: () => ({
    // ---- 6 态核心状态 ----
    status: 'idle',          // idle | chatting | proposing | confirmed | archived | cancelled
    sessionId: null,         // 字符串（Mock 用 _genMockSessionId，真链路用 D-1 返回的 id）
    sessionType: 'TASK_CREATE', // TASK_CREATE | PROFILE_INTERVIEW（当前 S5 仅实现 TASK_CREATE）

    // ---- 沟通期（chatting） ----
    messages: [],            // [{role:'user'|'ai', content:string, ts:number}]
    typing: false,           // AI 正在输入 (typing=true 显示 老人"正在思考…"大emoji动效)
    reasoningText: '',       // proposing 态 D-4 推理流追加文本（逐字流式显示）
    patchPreview: null,      // chatting 态 patch_preview 事件 → {profileId, patch:{}, remove_keys:[]}

    // ---- 草稿（proposing） ----
    proposal: null,          // proposal 事件 → {
                             //   title, description,
                             //   tasks:[{title,description,priority,dueDate,assigneeProfileId,categoryId}],
                             //   profileSnapshot, profilePatch:{}, remove_keys:[] }

    // ---- 终态（confirmed/archived/cancelled） ----
    confirmedResult: null,   // D-5 返回 → {tasks:[], profileUpdates:[]}
    archivedResult: null,    // D-6 返回 → {profileUpdates:[]}

    // ---- 入口上下文（TaskView 内嵌时传入；/chat Wrapper 读 URL query 拼） ----
    initForm: null,          // {title, description, priority, dueDate, categoryId, assigneeProfileId}

    // ---- 错误 ----
    errorMsg: null
  }),

  getters: {
    isIdle:        (s) => s.status === 'idle',
    isChatting:    (s) => s.status === 'chatting',
    isProposing:   (s) => s.status === 'proposing',
    isConfirmed:   (s) => s.status === 'confirmed',
    isArchived:    (s) => s.status === 'archived',
    isCancelled:   (s) => s.status === 'cancelled',
    isFinal:       (s) => ['confirmed','archived','cancelled'].includes(s.status),
    isInProgress:  (s) => ['chatting','proposing'].includes(s.status), // 离开页面二次确认判定
    hasMessages:   (s) => Array.isArray(s.messages) && s.messages.length > 0,
    hasProposal:   (s) => !!s.proposal && s.status === 'proposing',
    proposalTaskCount: (s) => s.proposal && Array.isArray(s.proposal.tasks) ? s.proposal.tasks.length : 0
  },

  actions: {
    /* =============================================================
     * 初始化：每次打开面板应先调用（full reset 防跨会话串台）
     * ============================================================= */
    pauseStream() {
      _abortSse()
      this.typing = false
    },
    async restoreSession(id) {
      const version = ++_initVersion
      const result = await getSession(id)
      if (version !== _initVersion) return
      this.sessionId = Number(id)
      this.initForm = { title: result.init_task_title || '' }
      this.messages = result.messages || []
      this.proposal = result.proposed_tasks?.length ? { tasks: result.proposed_tasks } : null
      this.status = result.status === 'CONFIRMED' ? 'confirmed'
        : result.status === 'CANCELLED' ? 'cancelled' : this.proposal ? 'proposing' : 'chatting'
    },
    async init({ sessionType = 'TASK_CREATE', initForm = null } = {}) {
      this.resetAllClean(true) // silent = true 不弹已重置提示
      const version = ++_initVersion
      this.sessionType = sessionType
      this.initForm = initForm || null
      // 真链路：调 D-1 创建会话获取真实 sessionId
      try {
        const resp = await createSession(sessionType, initForm || {})
        if (version !== _initVersion) return false
        this.sessionId = (resp && resp.session_id) ? resp.session_id : null
        if (!this.sessionId) throw new Error('会话未创建成功')
        return true
      } catch (e) {
        if (version !== _initVersion) return false
        this.sessionId = null
        this._setError('准备安排失败，请稍后重试')
        return false
      }
    },

    /* =============================================================
     * 发送用户消息（用户驱动多轮沟通 = Mock C 默认模式）
     * ============================================================= */
    async sendUserMessage(text) {
      const msg = (text || '').trim()
      if (!msg) {
        ElMessage.warning('请先输入要和 AI 沟通的内容哦～')
        return
      }
      if (!Number.isSafeInteger(Number(this.sessionId)) || Number(this.sessionId) < 1) {
        this._setError('请先开始一份新安排，再发送消息')
        return
      }
      if (this.typing) return
      // 状态迁移：idle → chatting；已是 chatting/proposing 不变
      if (this.status === 'idle') this.status = 'chatting'
      if (this.status !== 'chatting' && this.status !== 'idle') {
        ElMessage.warning('当前状态不允许发送新消息，先取消或完成草稿再来')
        return
      }
      // 1) 用户消息入栈
      this.messages.push({ role: 'user', content: msg, ts: Date.now() })

      // 2) 中止上一轮未完成的 SSE + demo timer（防止重入串台）
      _abortSse()
      _clearDemoTimers()
      this.typing = true
      this.reasoningText = ''

      // 3) 调真实 SSE 端点 POST /api/agent/v1/decompose/start（带 session_id 让 agent-service 存草稿到 session）
      const version = _streamVersion
      _sseController = _realSseStream(msg, this.sessionId, {
        onEvent: (type, dataStr) => { if (version === _streamVersion) this.onSseEvent(type, dataStr) },
        onError: () => {
          if (version !== _streamVersion) return
          this.typing = false
          this._setError('这次安排没有完成，请稍后重试；已保存的草稿仍可重新打开')
        },
        onClose: () => { if (version === _streamVersion) this.typing = false }
      })
    },

    /* =============================================================
     * 「▶️ 一键演示」—— 发送一条示例消息，真实 SSE 自动返回推理 + 草稿
     * ============================================================= */
    async runOneClickDemo() {
      _clearDemoTimers()
      _abortSse()
      this.resetAllClean(true)
      ElMessage.success('已进入「▶️ 一键演示」，AI 正在为你拆解任务～')
      // 演示也需要创建 session，否则 sessionId=null 会走 B-1 直接写业务表
      await this.init({ sessionType: 'TASK_CREATE', initForm: { title: '宝宝辅食添加计划' } })
      await this.sendUserMessage('我家宝宝 6 个月了，要制定一份辅食添加计划，包含辅食种类、时间和注意事项')
    },

    /* =============================================================
     * 生成拆解草稿 — 真实 SSE 已在 sendUserMessage 中返回 proposal，此函数保留兼容
     * ============================================================= */
    async generateDecompose() {
      // 真实链路：proposal 已由 SSE 的 proposal 事件设置，无需单独生成
      if (this.proposal) {
        ElMessage.info('草稿已生成，请在下方确认或修改')
        return
      }
      if (this.status === 'idle') {
        ElMessage.warning('请先和 AI 简单沟通任务背景，或点「▶️ 一键演示」')
        return
      }
      ElMessage.info('AI 正在思考中，请稍候…')
    },

    /* =============================================================
     * 回退到沟通态（proposing → chatting）：用户想继续和 AI 沟通调整草稿
     * 保留 messages/sessionId/initForm，清 proposal/reasoningText，
     * 用户可再次发消息 → agent-service 基于新消息重新生成 proposal 覆盖旧草稿。
     * ============================================================= */
    backToChatting() {
      if (this.status !== 'proposing') {
        ElMessage.info('当前没有可调整的草稿哦')
        return
      }
      this.status = 'chatting'
      this.proposal = null
      this.reasoningText = ''
      this.typing = false
      ElMessage.success('已回到沟通态，继续和 AI 说说你的想法吧～')
    },

    /* =============================================================
     * D-5 确认草稿（proposing → confirmed，写业务表）—— 带二次确认
     * ============================================================= */
    async confirmProposal() {
      if (this.status !== 'proposing' || !this.proposal) {
        ElMessage.warning('没有可确认的草稿哦')
        return
      }
      // 二次确认（Con7 第 1/5：cancelButtonClass 中文安全词 默认焦点=取消）
      try {
        await ElMessageBox.confirm(
          `确认把 ${this.proposal.tasks?.length || 0} 条草稿任务写入待办列表吗？\n请确认每条安排是否合适。`,
          '✅ 确认生成待办',
          {
            confirmButtonText: '确认生成',
            cancelButtonText: '我再改改',
            cancelButtonClass: 'el-button--primary', // 取消按钮默认焦点 = 老人误点安全
            distinguishCancelAndClose: true,
            type: 'success'
          }
        )
      } catch (e) { return /* 取消或关闭：什么都不做 */ }

      try {
        this.typing = true
        // Mock 会话跳过后端（sessionId 非数字，后端会 400/500）
        if (!this.sessionId) throw new Error('会话不存在')
        const resp = await confirm(this.sessionId)
        this.confirmedResult = resp || {}
        this.status = 'confirmed'
        const count = resp?.created_task_ids?.length || 0
        const profiles = resp?.merged_profile_ids?.length || 0
        ElMessage.success(`已创建 ${count} 条待办${profiles ? `，更新 ${profiles} 份档案` : ''}`)
      } catch (e) {
        // 拦截器已提示中文错误
      } finally {
        this.typing = false
        _abortSse()
        _clearDemoTimers()
      }
    },

    /* =============================================================
     * D-6 只存档案（proposing → archived，不生成待办任务）—— 带二次确认
     * ============================================================= */
    async saveOnlyProfile() {
      if (this.status !== 'proposing' || !this.proposal) {
        ElMessage.warning('没有可保存的档案变更哦')
        return
      }
      const hasPatch = !!(this.proposal.profilePatch && Object.keys(this.proposal.profilePatch).length)
                    || !!(this.proposal.remove_keys && this.proposal.remove_keys.length)
      if (!hasPatch) {
        ElMessage.warning('本次草稿没有档案变更内容，无需做「只存档案」哦')
        return
      }
      try {
        await ElMessageBox.confirm(
          '确认只把档案变更写入人员档案、不生成待办任务吗？',
          '📋 仅更新档案确认',
          {
            confirmButtonText: '只更新档案',
            cancelButtonText: '我再想想',
            cancelButtonClass: 'el-button--primary',
            distinguishCancelAndClose: true,
            type: 'warning'
          }
        )
      } catch (e) { return }

      try {
        this.typing = true
        // Mock 会话跳过后端（sessionId 非数字，后端会 400/500）
        const resp = (this.sessionId && !String(this.sessionId).startsWith('mock-session-'))
          ? await saveProfileOnly(this.sessionId)
          : {}
        this.archivedResult = resp || {}
        this.status = 'archived'
        ElMessage.success('人员档案已更新，未生成待办任务～')
      } catch (e) { /* 拦截器已提示 */ }
      finally {
        this.typing = false
        _abortSse()
        _clearDemoTimers()
      }
    },

    /* =============================================================
     * D-7 取消会话（chatting/proposing → cancelled）—— messages.length>1 二次确认
     * ============================================================= */
    async cancelSession() {
      if (this.isFinal) return
      // Con7 第 2/5：非终态 + 已有沟通记录（>1=至少1轮user+ai）→ 二次确认
      if (this.messages.length > 1 || this.hasProposal) {
        try {
          await ElMessageBox.confirm(
            '确认放弃本次拆解吗？放弃后已有的沟通记录和草稿都不会保存哦～',
            '❌ 放弃拆解确认',
            {
              confirmButtonText: '确认放弃',
              cancelButtonText: '继续沟通',
              cancelButtonClass: 'el-button--primary', // 默认焦点=取消(安全按钮)
              distinguishCancelAndClose: true,
              type: 'warning'
            }
          )
        } catch (e) { return }
      }
      try {
        // D-7 真端点：Mock 会话 ID（mock-session-xxx）后端无记录且非数字，
        // 调用会 400/500，故 Mock 模式跳过；真链路才发请求
        if (this.sessionId && !String(this.sessionId).startsWith('mock-session-')) {
          await cancelApi(this.sessionId).catch(() => {})
        }
      } catch (e) { /* 静默 */ }
      finally {
        _abortSse()
        _clearDemoTimers()
        this.status = 'cancelled'
        this.typing = false
      }
    },

    /* =============================================================
     * 约束 8：onSseEvent 统一分发（1 套 switch；chatStreamMock + decomposeStreamMock 共用）
     *          前端不写 if(MOCK)…else… 判断分支。
     * ============================================================= */
    onSseEvent(type, dataStr) {
      let data = null
      // 所有事件的 payload 都是 JSON 字符串（与后端 SSE 事件 1:1 约定，plans §3.3）
      if (typeof dataStr === 'string' && dataStr.length > 0) {
        try { data = JSON.parse(dataStr) } catch { data = { raw: dataStr } }
      }

      switch (String(type || '')) {
        case 'typing':
          this.typing = !!(data && data.typing !== false)
          break

        case 'message':
          // data = { role?:'ai', content:string }
          this.typing = false
          if (data && typeof data.content === 'string') {
            this.messages.push({ role: data.role || 'ai', content: data.content, ts: Date.now() })
          }
          break

        case 'patch_preview':
          // data = { profileId, patch:{}, remove_keys:[] }
          if (data && typeof data === 'object') {
            this.patchPreview = {
              profileId: data.profileId || null,
              patch: data.patch || {},
              remove_keys: Array.isArray(data.remove_keys) ? data.remove_keys : []
            }
            // 给老人一个"档案变更预览"轻提示（非阻塞 ElMessage，不是二次确认）
            const patchKeyCount = this.patchPreview.patch ? Object.keys(this.patchPreview.patch).length : 0
            const removeCount = this.patchPreview.remove_keys.length
            if (patchKeyCount + removeCount > 0) {
              ElMessage.info(`🤖 AI 建议更新档案：新增/修改 ${patchKeyCount} 项，移除 ${removeCount} 项`)
            }
          }
          break

        case 'reasoning':
          // 推理过程：真实 agent-service 用 delta 字段，Mock 用 text/reasoning
          const seg = (data && (data.delta || data.text || data.reasoning))
            ? (data.delta || data.text || data.reasoning)
            : (typeof dataStr === 'string' ? dataStr : '')
          if (seg) this.reasoningText += seg
          break

        case 'proposal':
          // 草稿完成：真实 agent-service 返回 {title, subtasks:[]}，Mock 返回 {title, tasks:[]}
          if (data && typeof data === 'object') {
            const tasks = Array.isArray(data.tasks) ? data.tasks
              : Array.isArray(data.subtasks) ? data.subtasks : null
            if (tasks) {
              this.proposal = { ...data, tasks }
              this.status = 'proposing'
            } else {
              this._setError('草稿格式不对哦，tasks/subtasks 字段不是数组')
            }
          }
          this.typing = false
          break

        case 'error':
          this._setError((data && data.msg) ? data.msg : (data && typeof data === 'string' ? data : '未知错误'))
          this.typing = false
          break

        case 'done':
          this.typing = false
          // done 事件不推进终态：由 confirmProposal / saveOnlyProfile / cancelSession 终态动作显式推进
          // 避免出现 done 事件就强制 confirmed 导致 D-6/D-7 不可用的半写问题
          break

        default:
          // 未知事件：静默忽略（后端新增事件时前端不炸，Graceful）
          break
      }
    },

    /* =============================================================
     * Con9 强制：resetAllClean() — Abort 所有 timers + SSE，回到 idle（full reset 防串台）
     * ============================================================= */
    resetAllClean(silent = false) {
      _initVersion += 1
      _clearDemoTimers()
      _abortSse()
      this.status = 'idle'
      this.sessionId = null
      this.sessionType = 'TASK_CREATE'
      this.messages = []
      this.typing = false
      this.reasoningText = ''
      this.patchPreview = null
      this.proposal = null
      this.confirmedResult = null
      this.archivedResult = null
      this.initForm = null
      this.errorMsg = null
      if (!silent) ElMessage.info('已重置 AI 拆解会话')
    },

    /* =============================================================
     * Con7 离开页面二次确认：面板 onBeforeUnmount / route.beforeRouteLeave 调
     * 返回 true=允许离开 false=不允许离开；内部弹 ElMessageBox
     * ============================================================= */
    async confirmLeave() {
      this.pauseStream()
      return true
    },

    // ============ 内部 helper（私有约定下划线，不暴露给外部组件 API 面）============
    _buildChatOpts() {
      // 把 initForm + 当前已有 patchPreview 传给 chatStreamMock 的 opts
      // （demoRoundIndex 由 chatStreamMock 内部 opts.initProfileSnapshot 推导）
      return {
        initProfileSnapshot: (this.patchPreview && Object.keys(this.patchPreview.patch || {}).length)
          ? this.patchPreview.patch
          : undefined,
        initTaskContext: this.initForm || undefined
      }
    },
    _setError(msg) {
      this.errorMsg = msg || '未知错误'
      ElMessage.error(msg || '发生了一个小错误，稍等一下再试试')
    }
  }
})

export default useDecomposeSseStore
