import { describe, it, expect } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createMemoryHistory } from 'vue-router';
import MoreMenuView from '@/views/MoreMenuView.vue';

function mountWithRouter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/more', component: MoreMenuView },
      { path: '/templates', component: { template: '<div/>' } },
      { path: '/automation', component: { template: '<div/>' } },
      { path: '/categories', component: { template: '<div/>' } },
      { path: '/families', component: { template: '<div/>' } },
      { path: '/calendar', component: { template: '<div/>' } },
      { path: '/password', component: { template: '<div/>' } }
    ]
  });
  router.push('/more');
  return router.isReady().then(() => {
    const wrapper = mount(MoreMenuView, { global: { plugins: [router] } });
    return { wrapper, router };
  });
}

describe('MoreMenuView D2 更多聚合页', () => {
  it('用例1: 渲染 6 个低频入口（日程/模板管理/自动化/分类管理/家庭管理/修改密码）', async () => {
    const { wrapper } = await mountWithRouter();
    const cards = wrapper.findAll('.more-card');
    // Change 13 Task 8 新增「日程」入口；后续追加「修改密码」入口，聚合页由 5 张卡变 6 张
    expect(cards).toHaveLength(9);
    const text = wrapper.text();
    expect(text).toContain('我的一天');
    expect(text).toContain('日程');
    expect(text).toContain('模板管理');
    expect(text).toContain('自动化');
    expect(text).toContain('分类管理');
    expect(text).toContain('家庭管理');
    expect(text).toContain('修改密码');
  });

  it('用例2: 点击入口卡片跳转到对应路由（分类管理 -> /categories）', async () => {
    const { wrapper, router } = await mountWithRouter();
    await wrapper.findAll('.more-card').find(c => c.text().includes('分类管理')).trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/categories');
  });

  it('用例3: 修改密码入口存在且指向 /password', async () => {
    const { wrapper, router } = await mountWithRouter();
    const pwdCard = wrapper.findAll('.more-card').find(c => c.text().includes('修改密码'));
    expect(pwdCard).toBeTruthy();
    await pwdCard.trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/password');
  });
});
