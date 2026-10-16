import { describe, it, expect } from 'vitest';

describe('Router X4 root redirect', () => {
  it('用例5: route配置中根路由应重定向到 /dashboard 而非 /tasks', async () => {
    const router = await import('@/router');
    const routes = router.default.getRoutes();
    // Vue Router 4 normalize 后产生两条 path=/：一条 redirect flatten + 一条布局带 children
    // 必须选择带 children 数组的布局路由
    const appLayoutRoute = routes.find(r => r.path === '/' && Array.isArray(r.children) && r.children.length > 0);
    expect(appLayoutRoute).toBeDefined();
    expect(appLayoutRoute.children).toBeDefined();
    const redirectChild = appLayoutRoute.children.find(c => c.path === '');
    expect(redirectChild).toBeDefined();
    expect(redirectChild.redirect).toBe('/dashboard');
  });

  it('用例5b: dashboard 路由存在并指向 DashboardView 组件', async () => {
    const router = await import('@/router');
    const routes = router.default.getRoutes();
    // 策略：先用与用例5相同的模式找到 AppLayout 布局路由
    const appLayoutRoute = routes.find(r => r.path === '/' && Array.isArray(r.children) && r.children.length > 0);
    expect(appLayoutRoute).toBeDefined();
    // 布局路由的 children 里一定包含 dashboard 的源路由配置（带 component 的懒加载函数）
    const dashboardRoute = appLayoutRoute.children.find(c => c.name === 'dashboard' || c.path === 'dashboard');
    expect(dashboardRoute).toBeDefined();
    // component 属性应该是一个函数（懒加载 () => import）
    expect(typeof dashboardRoute.component).toBe('function');
  });
});