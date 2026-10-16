// 登录完成后仅恢复明确支持的站内页面，拒绝协议、反斜杠和编码绕过。
export function safeNext(value) {
  if (typeof value !== 'string' || value.length > 512) return '/'
  if (/[\\%\x00-\x20]/.test(value) || value.includes('..') || value.startsWith('//')) return '/'
  return /^\/(?:agent\/draft\/[1-9]\d*|tasks(?:\/[1-9]\d*\/edit)?|chat|calendar|dashboard)(?:\?[^#]*)?$/.test(value)
    ? value : '/'
}
