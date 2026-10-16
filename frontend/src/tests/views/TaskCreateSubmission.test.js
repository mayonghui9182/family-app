import { it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import TaskCreate from '@/views/TaskCreateView.vue'
import { tasksApi } from '@/api/tasks'
vi.mock('@/api/tasks', () => ({ tasksApi: { create: vi.fn() } }))
vi.mock('@/api/profiles', () => ({ listProfiles: vi.fn().mockResolvedValue([]) }))
vi.mock('@/api/categories', () => ({ categoryApi: { list: vi.fn().mockResolvedValue([]) } }))
vi.mock('@/api/suggestions', () => ({ getProfileSuggestions: vi.fn() }))
async function setup() {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/tasks/new', component: TaskCreate }, { path: '/tasks', component: { template: '<div>任务列表</div>' } }
  ] })
  await router.push('/tasks/new')
  const wrapper = mount(TaskCreate, { global: { plugins: [router, createPinia()], stubs: { DecomposeChatPanel: true } } })
  await flushPromises()
  await wrapper.get('input[placeholder^="任务标题"]').setValue('买青菜')
  return { wrapper, router }
}
beforeEach(() => vi.clearAllMocks())
it('慢请求期间只提交一次，锁定表单并显示保存状态', async () => {
  let finish
  tasksApi.create.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const { wrapper, router } = await setup()
  const button = wrapper.findAll('button').find(b => b.text().includes('创建任务'))
  await button.trigger('click')
  await button.trigger('click')
  expect(tasksApi.create).toHaveBeenCalledTimes(1)
  expect(wrapper.text()).toContain('正在保存')
  expect(wrapper.get('input[placeholder^="任务标题"]').element.disabled).toBe(true)
  finish({ id: 1 })
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/tasks')
  wrapper.unmount()
})
it('失败保留输入和当前位置，提示先查列表，随后可以重试', async () => {
  tasksApi.create.mockRejectedValueOnce(new Error('timeout')).mockResolvedValueOnce({ id: 2 })
  const { wrapper, router } = await setup()
  const button = wrapper.findAll('button').find(b => b.text().includes('创建任务'))
  await button.trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/tasks/new')
  expect(wrapper.get('input[placeholder^="任务标题"]').element.value).toBe('买青菜')
  expect(wrapper.get('[role="alert"]').text()).toContain('请先查看列表')
  expect(button.element.disabled).toBe(false)
  await button.trigger('click'); await flushPromises()
  expect(tasksApi.create).toHaveBeenCalledTimes(2)
  wrapper.unmount()
})
