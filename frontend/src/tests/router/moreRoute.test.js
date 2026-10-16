import { describe, it, expect } from 'vitest';

describe('Router /more 聚合页路由（Change 9）', () => {
  it('用例1: AppLayout 子路由包含 /more 且组件为懒加载函数', async () => {
    const router = (await import('@/router')).default;
    const routes = router.getRoutes();
    const moreRoute = routes.find(r => r.path === '/more' || r.name === 'more');
    expect(moreRoute).toBeDefined();
    expect(typeof moreRoute.components?.default ?? moreRoute.component).toBe('function');
  });
});
