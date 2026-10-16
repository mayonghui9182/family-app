import { describe, it, expect, beforeEach } from 'vitest';

describe('Router /wechat/* 承接页为登录守卫例外（Change 11）', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('用例1: /wechat/bind 与 /wechat/callback 均为公开路由，无 token 也不重定向 /login', async () => {
    const router = (await import('@/router')).default;
    const routes = router.getRoutes();

    const bind = routes.find(r => r.path === '/wechat/bind');
    const callback = routes.find(r => r.path === '/wechat/callback');
    expect(bind).toBeDefined();
    expect(callback).toBeDefined();
    // 公开标记（与 /login 同款 meta.public）
    expect(bind.meta.public).toBe(true);
    expect(callback.meta.public).toBe(true);

    // 守卫行为：无 token 访问两页面不被踢去 /login
    localStorage.removeItem('accessToken');
    for (const path of ['/wechat/bind', '/wechat/callback']) {
      const resolved = router.resolve(path);
      expect(resolved.meta.requiresAuth).toBeFalsy();
      expect(resolved.path).toBe(path);
    }
  });
});
