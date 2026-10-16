import { ref, onScopeDispose } from 'vue'

/**
 * 响应式移动端判断。
 * 修复点：必须监听 matchMedia 的 change 事件。
 * 微信内置浏览器首次加载时视口可能未稳定，matchMedia('(max-width:768px)')
 * 会短暂返回 false（桌面布局），之后才稳定为 true；若不监听 change，
 * 首次进入会一直显示桌面布局，刷新后才正常。
 */
export function useIsMobile() {
  const isMobile = ref(false)
  const mql = window.matchMedia('(max-width: 768px)')
  isMobile.value = mql.matches

  const update = () => {
    isMobile.value = mql.matches
  }

  // 兼容旧版 Safari（addListener）和现代浏览器（addEventListener）
  if (mql.addEventListener) {
    mql.addEventListener('change', update)
  } else if (mql.addListener) {
    mql.addListener(update)
  }

  onScopeDispose(() => {
    if (mql.removeEventListener) {
      mql.removeEventListener('change', update)
    } else if (mql.removeListener) {
      mql.removeListener(update)
    }
  })

  return isMobile
}

export default useIsMobile
