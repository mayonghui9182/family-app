/**
 * 工作任务 AI 对话 SSE 客户端
 *
 * 端点：POST /api/work-tasks/chat（nginx → tp-agent-service:8101/api/chat）
 * 请求体：{ "message": "用户输入文本" }
 * SSE 事件序列：
 *   classification → {"category":"分类名","confidence":0.95}
 *   reasoning × N  → {"chunk":"推理文本块"}
 *   proposal       → {"task_id":"...","task_title":"...","subtasks":[...],...}
 *   done           → {"task_id":"...","status":"draft_created"}
 *   error（失败时） → {"message":"拆解失败，请重试"}
 *
 * 借鉴 decompose.js 的 fetch + ReadableStream + SSE 帧解析模式。
 * 不能用 axios（不支持 SSE 流式读取）。
 */

/**
 * 获取公共请求头（token + familyId，与 client.js 拦截器对齐）
 */
function commonHeaders() {
  return {
    'X-Access-Token': localStorage.getItem('accessToken') || '',
    'X-Family-Id': localStorage.getItem('currentFamilyId') || ''
  }
}

/**
 * 启动工作任务 AI 对话 SSE 流
 *
 * @param {string} message — 用户输入的消息文本
 * @param {Object} handlers — 事件回调
 * @param {Function} [handlers.onClassification] — (data: {category, confidence})
 * @param {Function} [handlers.onReasoning] — (chunk: string) 逐块追加
 * @param {Function} [handlers.onProposal] — (data: {task_id, task_title, subtasks, ...})
 * @param {Function} [handlers.onDone] — (data: {task_id, status})
 * @param {Function} [handlers.onError] — (msg: string)
 * @returns {Object} controller — { cancel() }
 */
export function streamWorkChat(message, handlers = {}) {
  const controller = new AbortController()

  async function connect() {
    try {
      const resp = await fetch('/api/work-tasks/chat', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...commonHeaders()
        },
        body: JSON.stringify({ message }),
        signal: controller.signal
      })

      if (!resp.ok) {
        const errBody = await resp.json().catch(() => ({}))
        throw new Error(errBody.detail || errBody.msg || `HTTP ${resp.status}`)
      }

      const reader = resp.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })

        // 解析 SSE 事件帧（以 \n\n 分隔）
        let idx
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          const rawEvent = buffer.slice(0, idx)
          buffer = buffer.slice(idx + 2)

          const lines = rawEvent.split('\n')
          let eventType = 'message'
          let dataStr = ''
          for (const line of lines) {
            if (line.startsWith('event:')) {
              eventType = line.slice(6).trim()
            } else if (line.startsWith('data:')) {
              dataStr += line.slice(5).trim()
            }
          }

          // task-planner 的 data 是 JSON，需要解析
          let data = null
          try {
            data = dataStr ? JSON.parse(dataStr) : null
          } catch {
            data = { raw: dataStr }
          }

          // 按事件类型分发
          switch (eventType) {
            case 'classification':
              handlers.onClassification?.(data)
              break
            case 'reasoning':
              handlers.onReasoning?.(data?.chunk || '')
              break
            case 'proposal':
              handlers.onProposal?.(data)
              break
            case 'done':
              handlers.onDone?.(data)
              return
            case 'error':
              handlers.onError?.(data?.message || '拆解失败，请重试')
              return
          }
        }
      }
    } catch (err) {
      if (err.name === 'AbortError') return
      handlers.onError?.(err.message || '网络不好，请稍后再试')
    }
  }

  connect()

  return {
    cancel: () => controller.abort()
  }
}

export default { streamWorkChat }
