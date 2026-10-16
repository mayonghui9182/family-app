import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createRouter, createMemoryHistory } from 'vue-router';
import { createPinia } from 'pinia';
import AppLayout from '@/layouts/AppLayout.vue';

function mockMatchMedia(matches) {
  const listeners = new Set();
  const mql = {
    matches,
    addEventListener: (_ev, cb) => listeners.add(cb),
    removeEventListener: (_ev, cb) => listeners.delete(cb),
    dispatchChange(m) { mql.matches = m; listeners.forEach(cb => cb({ matches: m })); }
  };
  window.matchMedia = vi.fn(() => mql);
  return mql;
}

function mountLayout() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: AppLayout, children: [
        { path: 'dashboard', component: { template: '<div/>' } }
      ] }
    ]
  });
  router.push('/dashboard');
  return router.isReady().then(() => mount(AppLayout, {
    global: {
      plugins: [router, createPinia()],
      // AppHeader 依赖 familyStore 网络初始化，与响应式布局无关，stub 隔离
      stubs: { AppHeader: true, RouterView: true }
    }
  }));
}

describe('AppLayout D1 响应式布局切换', () => {
  let originalMatchMedia;
  beforeEach(() => { originalMatchMedia = window.matchMedia; });
  afterEach(() => { window.matchMedia = originalMatchMedia; vi.restoreAllMocks(); });

  it('用例1: isMobile=true 时 el-aside 不渲染且 BottomTabBar 渲染', async () => {
    mockMatchMedia(true);
    const wrapper = await mountLayout();
    expect(wrapper.find('.app-aside-wrap').exists()).toBe(false);
    expect(wrapper.find('.bottom-tab-bar').exists()).toBe(true);
    expect(wrapper.find('.app-main-wrap').classes()).toContain('app-main-mobile');
  });

  it('用例2: isMobile=false 时 el-aside 渲染且无 BottomTabBar（桌面零回归）', async () => {
    mockMatchMedia(false);
    const wrapper = await mountLayout();
    expect(wrapper.find('.app-aside-wrap').exists()).toBe(true);
    expect(wrapper.find('.bottom-tab-bar').exists()).toBe(false);
    expect(wrapper.find('.app-main-wrap').classes()).not.toContain('app-main-mobile');
  });
});
