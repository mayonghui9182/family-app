import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createMemoryHistory } from 'vue-router';
import { createPinia } from 'pinia';

// mock API 层：数据源双路径（桌面网格 / 移动卡片）必须同源
// 注：vi.mock factory 会被提升到文件顶部，mock 数据必须放在 factory 内部（TDZ）
vi.mock('@/api/tasks', () => ({
  tasksApi: {
    page: vi.fn().mockResolvedValue({ total: 2, items: [
      { id: 1, title: '早上吃药', status: 'PENDING', assigneeProfile: { id: 9, name: '妈妈' } },
      { id: 2, title: '晚间散步', status: 'COMPLETED' }
    ] }),
    create: vi.fn().mockResolvedValue({}),
    del: vi.fn().mockResolvedValue({}),
    updateStatus: vi.fn().mockResolvedValue({})
  }
}));
vi.mock('@/api/categories', () => ({
  categoryApi: { list: vi.fn().mockResolvedValue([]) }
}));
vi.mock('@/api/profiles', () => ({
  listProfileOptions: vi.fn().mockResolvedValue([])
}));
vi.mock('@/api/suggestions', () => ({
  getProfileSuggestions: vi.fn().mockResolvedValue({})
}));

import TaskView from '@/views/TaskView.vue';
import { tasksApi } from '@/api/tasks';

function mockMatchMedia(matches) {
  const listeners = new Set();
  const mql = {
    matches,
    addEventListener: (_ev, cb) => listeners.add(cb),
    removeEventListener: (_ev, cb) => listeners.delete(cb)
  };
  window.matchMedia = vi.fn(() => mql);
  return mql;
}

async function mountTaskView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/tasks', name: 'tasks', component: TaskView },
      { path: '/chat', component: { template: '<div/>' } }
    ]
  });
  router.push('/tasks');
  await router.isReady();
  const wrapper = mount(TaskView, {
    global: { plugins: [router, createPinia()] }
  });
  await flushPromises(); // 等 onMounted 的 list/categories/profiles 拉取完成
  return wrapper;
}

describe('TaskView D3 移动端/桌面双模板', () => {
  let originalMatchMedia;
  beforeEach(() => {
    originalMatchMedia = window.matchMedia;
    vi.clearAllMocks();
  });
  afterEach(() => {
    // 仅手动恢复 matchMedia；不使用 vi.restoreAllMocks()——它会把 factory 中
    // vi.fn().mockResolvedValue(...) 的实现一并重置，导致后续用例 list() 返回 undefined
    window.matchMedia = originalMatchMedia;
  });

  it('用例1: isMobile=true 渲染移动卡片列表且无桌面网格；数据源与桌面一致（2 条任务 -> 2 张卡片）', async () => {
    mockMatchMedia(true);
    const wrapper = await mountTaskView();
    const cards = wrapper.findAll('.task-card-mobile');
    expect(cards).toHaveLength(2);
    expect(wrapper.find('.task-grid').exists()).toBe(false);
    expect(cards[0].text()).toContain('早上吃药');
    expect(cards[0].text()).toContain('妈妈');
  });

  it('用例2: isMobile=false 走原 el-card 网格，无移动卡片（桌面零回归）', async () => {
    mockMatchMedia(false);
    const wrapper = await mountTaskView();
    expect(wrapper.find('.task-grid').exists()).toBe(true);
    expect(wrapper.findAll('.task-card-mobile')).toHaveLength(0);
    // 桌面卡片同样渲染 2 条（数据源相同）
    expect(wrapper.findAll('.task-card').length).toBeGreaterThanOrEqual(2);
  });

  it('用例3: 移动端点击卡片"完成" -> tasksApi.updateStatus 被调用（与桌面同一流转守卫）', async () => {
    mockMatchMedia(true);
    const wrapper = await mountTaskView();
    const firstCard = wrapper.findAll('.task-card-mobile')[0];
    await firstCard.findAll('.el-button').find(b => b.text().includes('完成')).trigger('click');
    await flushPromises();
    expect(tasksApi.updateStatus).toHaveBeenCalledWith(1, 'COMPLETED');
  });
});
