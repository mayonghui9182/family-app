import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// mock element-plus 的 ElMessage / ElMessageBox，避免弹窗干扰
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}))

// mock API 模块（backToChatting 不调 API，但 store import 了它们）
vi.mock('../../api/decomposeSession.js', () => ({
  createSession: vi.fn(),
  confirm: vi.fn(),
  saveProfileOnly: vi.fn(),
  cancel: vi.fn(),
}))

vi.mock('../../api/client.js', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}))

import { useDecomposeSseStore } from '../../stores/decomposeSse'

describe('decomposeSse store - backToChatting（proposing → chatting 回退）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('用例1: proposing 态调用 backToChatting → status=chatting，清 proposal/reasoningText，保留 messages/sessionId/initForm', () => {
    const store = useDecomposeSseStore()
    // 模拟 proposing 态
    store.status = 'proposing'
    store.sessionId = 12345
    store.initForm = { title: '宝宝辅食计划' }
    store.messages = [
      { role: 'user', content: '帮我拆解辅食计划', ts: 1 },
      { role: 'ai', content: '好的，以下是建议', ts: 2 },
    ]
    store.proposal = { title: '辅食计划', tasks: [{ title: '买米粉' }] }
    store.reasoningText = '分析中…'

    store.backToChatting()

    expect(store.status).toBe('chatting')
    expect(store.proposal).toBeNull()
    expect(store.reasoningText).toBe('')
    // 保留沟通上下文，用户可继续发消息
    expect(store.messages).toHaveLength(2)
    expect(store.sessionId).toBe(12345)
    expect(store.initForm).toEqual({ title: '宝宝辅食计划' })
  })

  it('用例2: 非 proposing 态调用 backToChatting → 不改变状态（只给提示）', () => {
    const store = useDecomposeSseStore()
    store.status = 'chatting'
    store.sessionId = 99
    store.backToChatting()
    // 状态不变
    expect(store.status).toBe('chatting')
    expect(store.sessionId).toBe(99)
  })

  it('用例3: backToChatting 后 canSendMessage 为真（sendUserMessage 守卫允许）', () => {
    const store = useDecomposeSseStore()
    store.status = 'proposing'
    store.sessionId = 1
    store.proposal = { title: 'x', tasks: [] }

    store.backToChatting()

    // sendUserMessage 的守卫条件：status 必须是 chatting 或 idle
    expect(['chatting', 'idle'].includes(store.status)).toBe(true)
  })
})
