import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { effectScope } from 'vue';

// 构造可控的 matchMedia mock：可手动派发 change 事件
function mockMatchMedia(initialMatches) {
  const listeners = new Set();
  const mql = {
    matches: initialMatches,
    media: '(max-width: 768px)',
    addEventListener: (_ev, cb) => listeners.add(cb),
    removeEventListener: (_ev, cb) => listeners.delete(cb),
    dispatchChange(matches) {
      mql.matches = matches;
      listeners.forEach(cb => cb({ matches }));
    }
  };
  window.matchMedia = vi.fn(() => mql);
  return mql;
}

describe('useIsMobile D1 响应式断点检测', () => {
  let originalMatchMedia;
  beforeEach(() => {
    originalMatchMedia = window.matchMedia;
  });
  afterEach(() => {
    window.matchMedia = originalMatchMedia;
    vi.restoreAllMocks();
  });

  it('用例1: matchMedia 返回 matches=true 时 isMobile 为 true', async () => {
    mockMatchMedia(true);
    const { useIsMobile } = await import('@/composables/useIsMobile.js');
    const scope = effectScope();
    const isMobile = scope.run(() => useIsMobile());
    expect(isMobile.value).toBe(true);
    scope.stop();
  });

  it('用例2: matchMedia 返回 matches=false 时 isMobile 为 false（桌面态）', async () => {
    mockMatchMedia(false);
    const { useIsMobile } = await import('@/composables/useIsMobile.js');
    const scope = effectScope();
    const isMobile = scope.run(() => useIsMobile());
    expect(isMobile.value).toBe(false);
    scope.stop();
  });

  it('用例3: 视口跨越断点派发 change 事件时 isMobile 响应更新', async () => {
    const mql = mockMatchMedia(false);
    const { useIsMobile } = await import('@/composables/useIsMobile.js');
    const scope = effectScope();
    const isMobile = scope.run(() => useIsMobile());
    expect(isMobile.value).toBe(false);
    mql.dispatchChange(true);
    expect(isMobile.value).toBe(true);
    mql.dispatchChange(false);
    expect(isMobile.value).toBe(false);
    scope.stop();
  });
});
