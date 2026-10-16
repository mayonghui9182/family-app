/**
 * SSE 指数退避重连工具（X8 补项）
 * BASE_DELAY=3000ms, 失败×2, MAX_DELAY=24000ms 封顶
 * 连续失败 3 次 → failCount=4 时进入一次 SLOW_MODE(30000)，之后稳定 MAX
 * 连接成功 → 全部归零；forceReconnect → 立即 BASE
 */
export const BASE_DELAY = 3000;
export const MAX_DELAY = 24000;
export const SLOW_MODE_DELAY = 30000;
export const SLOW_MODE_THRESHOLD = 3;

export function createReconnectState() {
  return { delay: BASE_DELAY, failCount: 0, slowMode: false };
}

export function nextDelay(state, event) {
  const { connected = false } = event || {};
  if (connected) {
    return { delay: BASE_DELAY, failCount: 0, slowMode: false };
  }
  const nextFailCount = state.failCount + 1;
  if (state.slowMode) {
    // 慢模式：刚进入慢模式的第1次失败（failCount 从 3→4）给 SLOW_MODE_DELAY
    // 之后（failCount>=5）稳定在 MAX_DELAY，符合用例4 "cap at MAX" 期望
    if (nextFailCount === 4) {
      return { delay: SLOW_MODE_DELAY, failCount: nextFailCount, slowMode: true };
    }
    return { delay: MAX_DELAY, failCount: nextFailCount, slowMode: true };
  }
  let nextD = state.delay * 2;
  if (nextD > MAX_DELAY) nextD = MAX_DELAY;
  const willSlow = nextFailCount >= SLOW_MODE_THRESHOLD;
  return { delay: nextD, failCount: nextFailCount, slowMode: willSlow };
}

export function forceReconnectNow(state) {
  return { delay: BASE_DELAY, failCount: 0, slowMode: false };
}