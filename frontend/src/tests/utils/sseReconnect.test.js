import { describe, it, expect } from 'vitest';
import { createReconnectState, nextDelay, forceReconnectNow } from '@/utils/sseReconnect';

describe('sseReconnect - exponential backoff', () => {
  const BASE_DELAY = 3000;
  const MAX_DELAY = 24000;
  const SLOW_MODE_DELAY = 30000;
  const SLOW_MODE_THRESHOLD = 3;

  it('用例1: 首次失败->BASE_DELAY=3000ms, 第2失败->6000ms, 第3失败->12000ms, 第4+失败->24000封顶', () => {
    const s = createReconnectState();
    // 初始状态
    expect(s.delay).toBe(BASE_DELAY);
    expect(s.failCount).toBe(0);
    expect(s.slowMode).toBe(false);

    // 第1次失败
    const r1 = nextDelay(s, { connected: false });
    expect(r1.delay).toBe(6000); // BASE*2
    expect(r1.failCount).toBe(1);

    // 第2次失败
    const r2 = nextDelay({ ...r1 }, { connected: false });
    expect(r2.delay).toBe(12000); // 6000*2

    // 第3次失败 -> slow mode 触发
    const r3 = nextDelay({ ...r2 }, { connected: false });
    expect(r3.delay).toBe(24000); // 12000*2
    expect(r3.slowMode).toBe(true);
    expect(r3.failCount).toBe(3);

    // 第4次失败 -> 仍在慢模式, delay固定30000
    const r4 = nextDelay({ ...r3 }, { connected: false });
    expect(r4.delay).toBe(SLOW_MODE_DELAY);
    expect(r4.slowMode).toBe(true);
  });

  it('用例2: 连续失败3次进入slow_mode=30000ms; forceReconnect立即重置delay=BASE_DELAY', () => {
    const s = createReconnectState();
    // 模拟3次失败
    let cur = { ...s };
    for (let i = 0; i < 3; i++) {
      cur = nextDelay(cur, { connected: false });
    }
    expect(cur.slowMode).toBe(true);
    expect(cur.delay).toBe(MAX_DELAY);

    // force reconnect
    const afterForce = forceReconnectNow({ ...cur });
    expect(afterForce.delay).toBe(BASE_DELAY);
    expect(afterForce.failCount).toBe(0);
    expect(afterForce.slowMode).toBe(false);
  });

  it('用例3: EventSource.onopen成功->重置delay=BASE_DELAY + slow_mode=false + fail_count=0', () => {
    const s = { failCount: 5, delay: 30000, slowMode: true };
    const reset = nextDelay({ ...s }, { connected: true });
    expect(reset.failCount).toBe(0);
    expect(reset.delay).toBe(BASE_DELAY);
    expect(reset.slowMode).toBe(false);
  });

  it('用例4: no-fail keeps MAX not growing beyond cap', () => {
    const s = { failCount: 10, delay: 24000, slowMode: true };
    const kept = nextDelay({ ...s }, { connected: false });
    expect(kept.delay).toBe(MAX_DELAY); // capped, never grows past MAX_DELAY without going to SLOW_MODE_DELAY
  });
});
