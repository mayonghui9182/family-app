import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';
import WechatBindView from '@/views/WechatBindView.vue';

// vi.mock 工厂会被提升：mock 实现必须全部写在工厂内部（模块级常量会 TDZ）
vi.mock('@/api/auth', () => ({
  authApi: { wechatBind: vi.fn() }
}));

import { authApi } from '@/api/auth';

async function mountView(query = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/wechat/bind', name: 'wechat-bind', component: WechatBindView },
      { path: '/', name: 'home', component: { template: '<div>home</div>' } }
    ]
  });
  await router.push({ path: '/wechat/bind', query });
  await router.isReady();
  const wrapper = mount(WechatBindView, {
    global: { plugins: [createPinia(), router] }
  });
  return { wrapper, router };
}

describe('WechatBindView 微信绑定页（Change 11）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  it('用例1: 票据 + 正确凭证提交成功 -> 存 token 并跳转首页', async () => {
    vi.mocked(authApi.wechatBind).mockResolvedValueOnce({
      accessToken: 'wx-fam-token-xyz',
      nickname: '张阿姨',
      userId: 7,
      families: [{ id: 1, name: '张阿姨的家' }]
    });
    const { wrapper, router } = await mountView({ ticket: 'TICKET-ABC' });

    await wrapper.find('input[placeholder="请输入邮箱"]').setValue('zhang@example.com');
    await wrapper.find('input[placeholder="请输入密码"]').setValue('password123');
    await wrapper.find('[data-testid="wx-submit"]').trigger('click');
    await flushPromises();

    expect(authApi.wechatBind).toHaveBeenCalledWith({
      ticket: 'TICKET-ABC',
      email: 'zhang@example.com',
      password: 'password123'
    });
    expect(localStorage.getItem('accessToken')).toBe('wx-fam-token-xyz');
    expect(router.currentRoute.value.path).toBe('/');
  });

  it('用例2: 密码错误（40113）-> 显示后端中文错误且表单仍可重试', async () => {
    vi.mocked(authApi.wechatBind).mockRejectedValueOnce({
      response: { data: { code: 40113, msg: '邮箱正确，但密码不对，再试试' } }
    });
    const { wrapper } = await mountView({ ticket: 'TICKET-ABC' });

    await wrapper.find('input[placeholder="请输入邮箱"]').setValue('zhang@example.com');
    await wrapper.find('input[placeholder="请输入密码"]').setValue('wrong-pass');
    await wrapper.find('[data-testid="wx-submit"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('邮箱正确，但密码不对，再试试');
    // 表单仍在（票据未失效，可改密码重试）
    expect(wrapper.find('[data-testid="wx-email"]').exists()).toBe(true);
    expect(localStorage.getItem('accessToken')).toBeNull();
  });

  it('用例3: 无票据（链接失效/直接访问）-> 提示从公众号菜单重新进入', async () => {
    const { wrapper } = await mountView({});

    expect(wrapper.text()).toContain('绑定链接已失效');
    expect(wrapper.text()).toContain('公众号菜单');
    // 不展示提交表单
    expect(wrapper.find('[data-testid="wx-submit"]').exists()).toBe(false);
  });
});
