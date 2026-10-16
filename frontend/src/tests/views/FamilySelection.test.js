import { beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import FamilyView from '@/views/FamilyView.vue'
import { familyApi } from '@/api/family'
import { useAuthStore } from '@/stores/auth'
import { useFamilyStore } from '@/stores/family'

vi.mock('@/api/family', () => ({ familyApi: { list: vi.fn(), getDetail: vi.fn(), leave: vi.fn(), remove: vi.fn() } }))

beforeEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
  localStorage.clear()
  setActivePinia(createPinia())
})

it('刷新保留仍有效的选择，退出后选剩余家庭，无家庭时清空持久化选择', () => {
  const store = useFamilyStore()
  store.setCurrent({ id: 2, name: '旧名称' })
  store.reconcile([{ id: 1, name: '甲' }, { id: 2, name: '新名称' }])
  expect(store.currentFamily.name).toBe('新名称')
  store.reconcile([{ id: 1, name: '甲' }])
  expect(store.currentFamilyId).toBe('1')
  store.reconcile([])
  expect(store.currentFamilyId).toBe('')
  expect(localStorage.getItem('currentFamilyId')).toBeNull()
})

it('详情从成员身份显示管理员操作，退出成功后刷新失败仍清除旧家庭', async () => {
  const pinia = createPinia()
  const auth = useAuthStore(pinia)
  const family = useFamilyStore(pinia)
  auth.setUserInfo({ userId: 7 })
  family.setCurrent({ id: 1, name: '我的家' })
  familyApi.list.mockResolvedValue([{ id: 1, name: '我的家', role: 'OWNER' }])
  familyApi.getDetail.mockResolvedValue({ id: 1, name: '我的家', members: [{ userId: 7, nickname: '本人', role: 'OWNER' }] })
  familyApi.leave.mockResolvedValue({})
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/families', component: FamilyView }] })
  await router.push('/families')
  const wrapper = mount(FamilyView, { global: { plugins: [pinia, router] } })
  await flushPromises()
  expect(auth.families).toHaveLength(1)
  await wrapper.findAll('button').find(button => button.text().includes('详情')).trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('退出并清理空家庭')
  familyApi.list.mockRejectedValueOnce(new Error('刷新失败'))
  await wrapper.findAll('button').find(button => button.text().includes('退出并清理空家庭')).trigger('click')
  await flushPromises()
  expect(familyApi.leave).toHaveBeenCalledWith(1)
  expect(auth.families).toEqual([])
  expect(family.currentFamilyId).toBe('')
  wrapper.unmount()
})
