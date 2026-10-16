// 本地浏览器验收平台桩：所有账号和任务均为模拟数据，不代理真实业务服务。
const http = require('node:http')
const today = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
const items = [
  { key: 'work:1', source: 'work', id: '1', title: '准备下午的项目会议', status: 'PENDING', priority: 'HIGH', focus: true },
  { key: 'work:2', source: 'work', id: '2', title: '整理本周工作材料', status: 'IN_PROGRESS', priority: 'MEDIUM', focus: true },
  { unavailable: true }
]
const plan = { date: today, kind: 'PLAN', version: 0, confirmed: false, generatedAt: `${today}T00:30:00Z`, content: { baseline: true, late: false, items, initialItems: structuredClone(items) } }
const setting = { available: true, enabled: true, timezone: 'Asia/Shanghai' }
const server = http.createServer(async (req, res) => {
  let text = ''; for await (const chunk of req) text += chunk
  const body = text ? JSON.parse(text) : {}
  const path = req.url.split('?')[0]
  let data = null
  if (path.startsWith('/api/auth/')) data = { accessToken: 'a'.repeat(64), userId: 1, nickname: '页面验收', families: [] }
  else if (path === '/api/families') data = []
  else if (path === '/api/agent/v1/config') data = { ai_decompose_enabled: false }
  else if (path === '/api/daily/settings') { if (req.method === 'PUT') setting.enabled = body.enabled; data = setting }
  else if (path.includes('/daily/plans/')) {
    if (req.method === 'PUT') { plan.content.items = body.order.map(key => ({ ...plan.content.items.find(item => item.key === key), focus: body.focus.includes(key) })); plan.version++; plan.confirmed = false }
    if (path.endsWith('/confirm')) { plan.confirmed = true; plan.version++ }
    data = plan
  } else if (path.includes('/daily/reviews/')) data = { missing: true }
  else data = []
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
  res.end(JSON.stringify({ code: 0, msg: '模拟验收', data }))
})
server.listen(15388, '127.0.0.1', () => console.log('模拟验收 API 已启动：127.0.0.1:15388'))
process.on('SIGINT', () => server.close(() => process.exit(0)))
