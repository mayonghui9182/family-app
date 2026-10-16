import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia } from 'pinia'
import TaskView from '@/views/TaskView.vue'
import { useAuthStore } from '@/stores/auth'
import { tasksApi } from '@/api/tasks'
vi.mock('@/api/tasks', () => ({ tasksApi: { page: vi.fn(), updateStatus: vi.fn() } }))
async function setup(path) {
  window.matchMedia = vi.fn(() => ({ matches: true, addEventListener() {}, removeEventListener() {} }))
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/tasks', component: TaskView }] })
  await router.push(path)
  const pinia = createPinia()
  useAuthStore(pinia).setUserInfo({ userId: 7 })
  const wrapper = mount(TaskView, { global: { plugins: [router, pinia] } })
  await flushPromises()
  return { wrapper, router }
}
beforeEach(() => { vi.clearAllMocks(); tasksApi.page.mockResolvedValue({ items: [], total: 0 }) })
describe('任务筛选与错误恢复', () => {
  it('本人、逾期和状态同时传递，清除路由后不保留旧条件', async () => {
    const { router } = await setup('/tasks?scope=mine&overdue=true&status=PENDING')
    expect(tasksApi.page).toHaveBeenLastCalledWith({ assigneeId: 7, overdue: true, status: 'PENDING', page: 0, size: 20 })
    await router.push('/tasks'); await flushPromises()
    expect(tasksApi.page).toHaveBeenLastCalledWith({ page: 0, size: 20 })
  })
  it('加载失败显示重试，不显示无任务', async () => {
    tasksApi.page.mockRejectedValueOnce(new Error('断网'))
    const { wrapper } = await setup('/tasks')
    expect(wrapper.text()).toContain('任务没加载出来')
    expect(wrapper.text()).not.toContain('还没有任务')
    await wrapper.find('.list-error button').trigger('click'); await flushPromises()
    expect(wrapper.find('.list-error').exists()).toBe(false)
  })
  it('旧筛选的迟到结果不覆盖新筛选', async () => {
    let finish
    tasksApi.page.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const { wrapper, router } = await setup('/tasks')
    tasksApi.page.mockResolvedValueOnce({ items: [{ id: 2, title: '新筛选任务', status: 'PENDING' }], total: 1 })
    await router.push('/tasks?scope=mine'); await flushPromises()
    finish({ items: [{ id: 1, title: '过期请求任务', status: 'PENDING' }], total: 1 }); await flushPromises()
    expect(wrapper.text()).toContain('新筛选任务')
    expect(wrapper.text()).not.toContain('过期请求任务')
  })
})
