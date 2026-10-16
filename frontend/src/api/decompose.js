/**
 * C-25/26 SSE 拆解流 SDK
 *
 * C-25 POST /api/agent/v1/decompose/start — SSE 流式响应
 *   5 事件类型：classification / reasoning / proposal / done / error
 *   不能用 axios（不支持 SSE 流式读取），用 fetch + ReadableStream
 *
 * C-26 POST /api/agent/v1/decompose/{familyId}/{taskId}/cancel — 取消拆解
 */

/**
 * 获取公共请求头（token + familyId）
 */
function commonHeaders() {
  return {
    'X-Access-Token': localStorage.getItem('accessToken') || '',
    'X-Family-Id': localStorage.getItem('currentFamilyId') || ''
  }
}

/**
 * C-25 启动 SSE 拆解流
 *
 * @param {Object} body — { task_id, family_id, ... }
 * @param {Object} handlers — { onEvent(type, data), onError(err), onClose() }
 * @returns {Object} controller — { cancel() }
 */
export function startDecompose(body, handlers = {}) {
  const controller = new AbortController()

  async function connect() {
    try {
      const resp = await fetch('/api/agent/v1/decompose/start', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...commonHeaders()
        },
        body: JSON.stringify(body || {}),
        signal: controller.signal
      })

      if (!resp.ok) {
        const errBody = await resp.json().catch(() => ({}))
        throw new Error(errBody.msg || `HTTP ${resp.status}`)
      }

      const reader = resp.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })

        // 解析 SSE 事件（以 \n\n 分隔）
        let idx
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          const rawEvent = buffer.slice(0, idx)
          buffer = buffer.slice(idx + 2)

          const lines = rawEvent.split('\n')
          let eventType = 'message'
          let data = ''
          for (const line of lines) {
            if (line.startsWith('event:')) {
              eventType = line.slice(6).trim()
            } else if (line.startsWith('data:')) {
              data = line.slice(5).trim()
            }
          }
          if (handlers.onEvent) handlers.onEvent(eventType, data)

          if (eventType === 'done' || eventType === 'error') {
            if (handlers.onClose) handlers.onClose()
            return
          }
        }
      }
      if (handlers.onClose) handlers.onClose()
    } catch (err) {
      if (err.name === 'AbortError') return
      if (handlers.onError) handlers.onError(err)
    }
  }

  connect()

  return {
    cancel: () => controller.abort()
  }
}

/**
 * C-26 取消拆解
 */
export async function cancelDecompose(familyId, taskId) {
  const resp = await fetch(
    `/api/agent/v1/decompose/${familyId}/${taskId}/cancel`,
    {
      method: 'POST',
      headers: commonHeaders()
    }
  )
  const body = await resp.json()
  if (body.code !== 0) throw new Error(body.msg || '取消失败')
  return body.data
}

export default { startDecompose, cancelDecompose }
