import { it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import View from '@/views/PasswordRecoveryView.vue'
import client from '@/api/client'
vi.mock('@/api/client', () => ({ default: { post: vi.fn() } }))
async function setup(path) {
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/forgot-password',component:View},{path:'/reset-password',component:View},{path:'/login',component:{template:'<div />'}}]})
 await router.push(path)
 const wrapper=mount(View,{global:{plugins:[router,createPinia()]}})
 await flushPromises();return {wrapper,router}
}
beforeEach(()=>{vi.clearAllMocks();localStorage.clear();window.history.replaceState({},'', '/');client.post.mockResolvedValue({})})
it('邮箱申请统一受理，未配置时显示不可用',async()=>{
 const {wrapper}=await setup('/forgot-password')
 await wrapper.get('input').setValue('user@example.test')
 client.post.mockRejectedValueOnce({response:{data:{msg:'暂时无法找回密码，请稍后再试'}}})
 await wrapper.get('form').trigger('submit');await flushPromises()
 expect(wrapper.text()).toContain('暂时无法找回密码')
 await wrapper.get('form').trigger('submit');await flushPromises()
 expect(wrapper.text()).toContain('如果此邮箱已注册')
 expect(client.post).toHaveBeenCalledWith('/auth/password-reset/request',{email:'user@example.test'})
 wrapper.unmount()
})
it('清除地址栏凭证，密码一致才提交，成功清除旧会话',async()=>{
 const token='a'.repeat(64);window.history.replaceState({},'', '/reset-password#token='+token)
 localStorage.setItem('accessToken','old-token')
 const {wrapper}=await setup('/reset-password')
 expect(window.location.hash).toBe('')
 const inputs=wrapper.findAll('input')
 await inputs[0].setValue('new-password');await inputs[1].setValue('different')
 await wrapper.get('form').trigger('submit');expect(client.post).not.toHaveBeenCalled()
 await inputs[1].setValue('new-password');await wrapper.get('form').trigger('submit');await flushPromises()
 expect(client.post).toHaveBeenCalledWith('/auth/password-reset/confirm',{token,password:'new-password'})
 expect(localStorage.getItem('accessToken')).toBeNull()
 expect(wrapper.text()).toContain('密码已更新')
 wrapper.unmount()
})
it('无凭证不显示重置表单，可切换回邮箱申请',async()=>{
 const {wrapper,router}=await setup('/reset-password')
 expect(wrapper.find('form').exists()).toBe(false)
 await router.push('/forgot-password');await flushPromises()
 expect(wrapper.find('input[type="email"]').exists()).toBe(true)
 wrapper.unmount()
})
