import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useDecomposeStore } from '../../stores/decompose'

// mock 共享 axios 客户端：store.init() 调 client.get('/agent/v1/config')
vi.mock('../../api/client', () => ({
  default: {
    get: vi.fn()
  }
}))

import client from '../../api/client'

describe('AI 拆解开关 store（Change 7 Task 2 子步骤 9）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    document.body.classList.remove('ai-disabled')
    vi.clearAllMocks()
  })

  it('1. /config 返回 ai_decompose_enabled=false -> body 加 ai-disabled class', async () => {
    vi.mocked(client.get).mockResolvedValueOnce({ ai_decompose_enabled: false })
    const store = useDecomposeStore()
    await store.init()
    expect(store.aiDecomposeEnabled).toBe(false)
    expect(store.loaded).toBe(true)
    expect(store.disabled).toBe(true)
    expect(document.body.classList.contains('ai-disabled')).toBe(true)
  })

  it('2. /config 返回 ai_decompose_enabled=true -> body 不加 ai-disabled class', async () => {
    vi.mocked(client.get).mockResolvedValueOnce({ ai_decompose_enabled: true })
    const store = useDecomposeStore()
    await store.init()
    expect(store.aiDecomposeEnabled).toBe(true)
    expect(store.disabled).toBe(false)
    expect(document.body.classList.contains('ai-disabled')).toBe(false)
  })

  it('3. /config 调用失败（网络异常）-> 保守视为关闭，body 加 ai-disabled class', async () => {
    vi.mocked(client.get).mockRejectedValueOnce(new Error('网络不好'))
    const store = useDecomposeStore()
    await store.init()
    expect(store.aiDecomposeEnabled).toBe(false)
    expect(store.disabled).toBe(true)
    expect(document.body.classList.contains('ai-disabled')).toBe(true)
  })
})
