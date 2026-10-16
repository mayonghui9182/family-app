import { it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import TaskCreate from '@/views/TaskCreateView.vue'
import TaskEdit from '@/views/TaskEditView.vue'
import { listProfiles, listProfileOptions } from '@/api/profiles'
vi.mock('@/api/profiles', () => ({
  listProfiles: vi.fn().mockResolvedValue([{ id: 8, name: '妈妈', profileType: 'ADULT', avatarEmoji: '👩' }]),
  listProfileOptions: vi.fn()
}))
vi.mock('@/api/categories', () => ({ categoryApi: { list: vi.fn().mockResolvedValue([]) } }))
vi.mock('@/api/tasks', () => ({ tasksApi: { get: vi.fn().mockResolvedValue({ title: '买菜' }) } }))
vi.mock('@/api/suggestions', () => ({ getProfileSuggestions: vi.fn().mockResolvedValue({}) }))
it('创建和编辑使用真实人员列表，不使用档案类型接口', async () => {
  for (const component of [TaskCreate, TaskEdit]) {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/tasks/:id/edit', component }] })
    await router.push('/tasks/1/edit')
    const wrapper = mount(component, { global: { plugins: [router, createPinia()], stubs: { DecomposeChatPanel: true } } })
    await flushPromises()
    expect(wrapper.findAllComponents({ name: 'ElOption' }).some(option => option.props('value') === 8 && option.props('label') === '妈妈')).toBe(true)
    wrapper.unmount()
  }
  expect(listProfiles).toHaveBeenCalledTimes(2)
  expect(listProfileOptions).not.toHaveBeenCalled()
})
