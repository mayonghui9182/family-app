import { describe, it, expect } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createMemoryHistory } from 'vue-router';
import BottomTabBar from '@/components/BottomTabBar.vue';

function mountWithRouter(initialPath = '/tasks') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/dashboard', component: { template: '<div/>' } },
      { path: '/tasks', component: { template: '<div/>' } },
      { path: '/chat', component: { template: '<div/>' } },
      { path: '/more', component: { template: '<div/>' } }
    ]
  });
  router.push(initialPath);
  return router.isReady().then(() => {
    const wrapper = mount(BottomTabBar, { global: { plugins: [router] } });
    return { wrapper, router };
  });
}

describe('BottomTabBar D2 移动端底部导航', () => {
  it('用例1: 渲染 4 个 Tab（首页/我的任务/AI拆解/更多）', async () => {
    const { wrapper } = await mountWithRouter();
    const items = wrapper.findAll('.tab-item');
    expect(items).toHaveLength(4);
    const text = wrapper.text();
    expect(text).toContain('首页');
    expect(text).toContain('我的任务');
    expect(text).toContain('日程');
    expect(text).toContain('更多');
  });

  it('用例2: 当前路由对应 Tab 高亮（/tasks -> 我的任务 active）', async () => {
    const { wrapper } = await mountWithRouter('/tasks');
    const items = wrapper.findAll('.tab-item');
    const tasksTab = items.find(i => i.text().includes('我的任务'));
    expect(tasksTab.classes()).toContain('active');
    expect(tasksTab.attributes('aria-current')).toBe('page');
    // 其余 Tab 不高亮
    const dashboardTab = items.find(i => i.text().includes('首页'));
    expect(dashboardTab.classes()).not.toContain('active');
  });

  it('用例3: 点击 Tab 触发路由跳转（点首页 -> /dashboard）', async () => {
    const { wrapper, router } = await mountWithRouter('/tasks');
    await wrapper.findAll('.tab-item').find(i => i.text().includes('首页')).trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/dashboard');
  });
});
