import { beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import LoginView from '@/views/LoginView.vue'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { useFamilyStore } from '@/stores/family'

vi.mock('@/api/auth', () => ({ authApi: { register: vi.fn() } }))
beforeEach(() => { vi.clearAllMocks(); localStorage.clear() })

it.each(['create', 'later', 'join'])('注册模式 %s 成功后清理旧家庭并离开表单，慢请求不重复提交', async mode => {
  const pinia = createPinia()
  const family = useFamilyStore(pinia)
  const auth = useAuthStore(pinia)
  family.setCurrent({ id: 99 })
  auth.setFamilies([{ id: 99 }])
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/login', component: LoginView },
    { path: '/', component: { template: '<div />' } },
    { path: '/families', component: { template: '<div />' } }
  ] })
  await router.push('/login')
  let finish
  authApi.register.mockReturnValue(new Promise(resolve => { finish = resolve }))
  const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } })
  Object.assign(wrapper.vm.regForm, { nickname: '测试家人', email: 'local@example.test', password: 'password123', familyMode: mode, inviteCode: 'ABCDEF' })
  const pending = wrapper.vm.onRegister()
  await wrapper.vm.onRegister()
  expect(authApi.register).toHaveBeenCalledTimes(1)
  expect(authApi.register.mock.calls[0][0].inviteCode).toBe(mode === 'join' ? 'ABCDEF' : null)
  finish({ accessToken: 'new-token', userId: 7, familyId: mode === 'later' ? null : 2 })
  await pending
  await flushPromises()
  expect(auth.token).toBe('new-token')
  expect(auth.families).toEqual([])
  expect(family.currentFamilyId).toBe('')
  expect(router.currentRoute.value.path).toBe(mode === 'later' ? '/families' : '/')
  wrapper.unmount()
})
