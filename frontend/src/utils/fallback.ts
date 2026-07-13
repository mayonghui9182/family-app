export async function withFallback<T>(
  apiCall: () => Promise<T>,
  fallback: () => T | Promise<T>,
  silent: boolean = true
): Promise<T> {
  try {
    return await apiCall()
  } catch (e) {
    console.warn('API调用失败，使用降级数据:', e)
    return await fallback()
  }
}
