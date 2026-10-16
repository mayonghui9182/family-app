import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DailyView from '../../views/DailyView.vue'
import client from '../../api/client'

vi.mock('../../api/client', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn() } }))

const plan = { version: 0, confirmed: false, generatedAt: '2026-10-10T00:30:00Z', content: { baseline: true, items: [
  { key: 'work:1', title: '准备会议', status: 'PENDING', source: 'work', focus: true }
] } }

describe('我的一天', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    client.get.mockImplementation(url => Promise.resolve(url.endsWith('settings') ? { available: true, enabled: true } : url.includes('plans') ? structuredClone(plan) : { missing: true }))
  })

  const create = () => mount(DailyView, { global: { stubs: { RouterLink: true } } })

  it('个人页面不需要家庭选择即可读取计划，调整不直接写任务', async () => {
    localStorage.clear()
    const wrapper = create(); await flushPromises()
    expect(wrapper.text()).toContain('准备会议')
    client.put.mockResolvedValue({ ...plan, version: 1 })
    await wrapper.findAll('button').find(button => button.text() === '保存调整').trigger('click')
    await flushPromises()
    expect(client.put.mock.calls[0][0]).toMatch(/^\/daily\/plans\//)
    expect(client.put.mock.calls[0][1].version).toBe(0)
    expect(client.post).not.toHaveBeenCalled()
  })

  it('切换日期加载失败时清除上一日期内容并提示错误', async () => {
    const wrapper = create(); await flushPromises()
    client.get.mockImplementation(url => url.endsWith('settings') ? Promise.resolve({ available: true }) : Promise.reject(new Error('来源不可用')))
    await wrapper.find('input[type=date]').setValue('2026-10-09'); await flushPromises()
    expect(wrapper.text()).not.toContain('准备会议')
    expect(wrapper.find('[role=alert]').text()).toContain('来源不可用')
  })
})
