import { it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import Draft from '@/views/AgentDraftDetailView.vue'
import { getSession, updateProposal, confirm } from '@/api/decomposeSession'
vi.mock('@/api/decomposeSession', () => ({
  getSession: vi.fn(), updateProposal: vi.fn().mockResolvedValue({}), confirm: vi.fn().mockResolvedValue({}), cancel: vi.fn()
}))
const data = { id: 9, status: 'ACTIVE', init_task_title: '周末买菜', proposed_tasks: [{ title: '买青菜' }] }
async function setup() {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/agent/draft/:id', component: Draft }, { path: '/tasks', component: { template: '<div/>' } }
  ] })
  await router.push('/agent/draft/9')
  const wrapper = mount(Draft, { global: { plugins: [router] } })
  await flushPromises(); return wrapper
}
beforeEach(() => { vi.clearAllMocks(); getSession.mockResolvedValue(data) })
it('读取原文，保存用户修改后确认，重复打开无确认按钮', async () => {
  const wrapper = await setup()
  expect(wrapper.text()).toContain('你说：周末买菜')
  await wrapper.find('input').setValue('买菠菜')
  getSession.mockResolvedValue({ ...data, status: 'CONFIRMED', proposed_tasks: [{ title: '买菠菜', created_task_id: 33 }] })
  await wrapper.findAll('button').find(b => b.text().includes('确认添加')).trigger('click'); await flushPromises()
  expect(updateProposal).toHaveBeenCalledWith('9', [{ title: '买菠菜' }])
  expect(confirm).toHaveBeenCalledTimes(1)
  expect(wrapper.text()).toContain('已经创建')
  expect(wrapper.find('.draft-actions').exists()).toBe(false)
  expect(wrapper.find('a[href="/tasks/33/edit"]').exists()).toBe(true)
})
it('空草稿不能确认，加载错误给重试入口', async () => {
  getSession.mockResolvedValue({ ...data, proposed_tasks: [] })
  const empty = await setup()
  expect(empty.text()).toContain('暂时还没有拆解出待办')
  expect(empty.find('.draft-actions button').attributes('disabled')).toBeDefined()
  empty.unmount()
  getSession.mockRejectedValue(new Error('46001'))
  const failed = await setup()
  expect(failed.text()).toContain('无法读取')
  expect(failed.text()).not.toContain('暂时还没有拆解出待办')
})

it('保存期间切换草稿，不继续确认另一个会话', async () => {
  let finishSave
  updateProposal.mockImplementationOnce(() => new Promise(resolve => { finishSave = resolve }))
  const wrapper = await setup()
  await wrapper.findAll('button').find(b => b.text().includes('确认添加')).trigger('click')
  await wrapper.vm.$router.push('/agent/draft/10')
  await flushPromises()
  finishSave({})
  await flushPromises()
  expect(updateProposal).toHaveBeenCalledWith('9', data.proposed_tasks)
  expect(confirm).not.toHaveBeenCalled()
  wrapper.unmount()
})
