import { beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import FamilyGate from '@/components/FamilyGate.vue'
import { familyApi } from '@/api/family'
import { useFamilyStore } from '@/stores/family'

vi.mock('@/api/family', () => ({ familyApi: { list: vi.fn() } }))
beforeEach(() => { vi.clearAllMocks(); localStorage.clear() })
async function setup(optional = false) {
  const pinia = createPinia()
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/tasks', component: { template: '<div />' }, meta: { familyOptional: optional } },
    { path: '/families', component: { template: '<div />' }, meta: { familyOptional: true } }
  ] })
  await router.push('/tasks')
  const mounted = vi.fn()
  const wrapper = mount(FamilyGate, { slots: { default: { template: '<div>任务页面</div>', mounted } }, global: { plugins: [pinia, router] } })
  return { wrapper, router, mounted, family: useFamilyStore(pinia) }
}

it('列表返回前不挂载业务页面，自动选择有效家庭后才加载', async () => {
  let resolve
  familyApi.list.mockReturnValue(new Promise(done => { resolve = done }))
  localStorage.setItem('currentFamilyId', '99')
  const { wrapper, mounted, family } = await setup()
  expect(mounted).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('正在读取')
  resolve([{ id: 2, name: '家人之家' }])
  await flushPromises()
  expect(family.currentFamilyId).toBe('2')
  expect(mounted).toHaveBeenCalledTimes(1)
  wrapper.unmount()
})

it('无家庭清空旧选择，展示加入入口，选择家庭后恢复原页面', async () => {
  familyApi.list.mockResolvedValue([])
  localStorage.setItem('currentFamilyId', '99')
  const { wrapper, mounted, family } = await setup()
  await flushPromises()
  expect(family.currentFamilyId).toBe('')
  expect(mounted).not.toHaveBeenCalled()
  expect(wrapper.get('a').attributes('href')).toBe('/families')
  family.setCurrent({ id: 3 })
  await flushPromises()
  expect(mounted).toHaveBeenCalledTimes(1)
  wrapper.unmount()
})

it('网络失败显示重试而不是无家庭，重试成功后加载页面', async () => {
  familyApi.list.mockRejectedValueOnce(new Error('网络异常')).mockResolvedValueOnce([{ id: 1 }])
  const { wrapper, mounted } = await setup()
  await flushPromises()
  expect(wrapper.text()).toContain('暂时无法读取家庭')
  expect(mounted).not.toHaveBeenCalled()
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(mounted).toHaveBeenCalledTimes(1)
  wrapper.unmount()
})

it('不依赖家庭的页面在列表失败时仍可使用', async () => {
  familyApi.list.mockRejectedValue(new Error('网络异常'))
  const { wrapper, mounted } = await setup(true)
  await flushPromises()
  expect(mounted).toHaveBeenCalledTimes(1)
  expect(wrapper.text()).toContain('任务页面')
  wrapper.unmount()
})
