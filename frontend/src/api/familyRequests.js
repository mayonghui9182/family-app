// 家庭切换时取消旧请求；版本号还能拦住 A → B → A 的迟到响应。
let generation = 0
const pending = new Set()
export function cancelFamilyRequests() {
  generation += 1
  for (const controller of pending) controller.abort()
  pending.clear()
}
export function trackFamilyRequest(config) {
  const controller = new AbortController()
  const external = config.signal
  const abort = () => controller.abort()
  if (external?.aborted) controller.abort()
  else external?.addEventListener('abort', abort, { once: true })
  config.signal = controller.signal
  config.familyGeneration = generation
  config.releaseFamilyRequest = () => {
    pending.delete(controller)
    external?.removeEventListener('abort', abort)
  }
  pending.add(controller)
}
export function finishFamilyRequest(config) {
  config?.releaseFamilyRequest?.()
  return config?.familyGeneration == null || config.familyGeneration === generation
}
