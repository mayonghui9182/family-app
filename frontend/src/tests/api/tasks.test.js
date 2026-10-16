import { describe, it, expect, beforeEach, vi } from 'vitest'
import client from '../../api/client'
import { tasksApi } from '../../api/tasks'

vi.mock('../../api/client', () => ({
  default: {
    post: vi.fn(),
    get: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  }
}))

describe('tasksApi mock 层', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('list: 无参数时 GET /tasks 带 params 空对象', async () => {
    vi.mocked(client.get).mockResolvedValueOnce([])
    const r = await tasksApi.list()
    expect(r).toEqual([])
    expect(client.get).toHaveBeenCalledWith('/tasks', { params: {} })
  })

  it('list: 带筛选参数时 GET /tasks?status=PENDING&overdue=true', async () => {
    vi.mocked(client.get).mockResolvedValueOnce([])
    await tasksApi.list({ status: 'PENDING', overdue: true, assigneeId: 1 })
    expect(client.get).toHaveBeenCalledWith('/tasks', {
      params: { status: 'PENDING', overdue: true, assigneeId: 1 }
    })
  })

  it('create: POST /tasks，payload 透传', async () => {
    const payload = { title: '洗碗', dueDate: '2026-08-25' }
    vi.mocked(client.post).mockResolvedValueOnce({ id: 1 })
    const r = await tasksApi.create(payload)
    expect(r.id).toBe(1)
    expect(client.post).toHaveBeenCalledWith('/tasks', payload)
  })

  it('get: GET /tasks/:id', async () => {
    vi.mocked(client.get).mockResolvedValueOnce({ id: 9, title: 't9' })
    const r = await tasksApi.get(9)
    expect(r.title).toBe('t9')
    expect(client.get).toHaveBeenCalledWith('/tasks/9')
  })

  it('update: PUT /tasks/:id', async () => {
    const payload = { title: '新标题' }
    vi.mocked(client.put).mockResolvedValueOnce({ id: 3, title: '新标题' })
    const r = await tasksApi.update(3, payload)
    expect(r.title).toBe('新标题')
    expect(client.put).toHaveBeenCalledWith('/tasks/3', payload)
  })

  it('del: DELETE /tasks/:id', async () => {
    vi.mocked(client.delete).mockResolvedValueOnce(true)
    const r = await tasksApi.del(5)
    expect(r).toBe(true)
    expect(client.delete).toHaveBeenCalledWith('/tasks/5')
  })

  it('updateStatus: POST /tasks/:id/status，body 为 { status }', async () => {
    vi.mocked(client.post).mockResolvedValueOnce({ id: 7, status: 'COMPLETED' })
    const r = await tasksApi.updateStatus(7, 'COMPLETED')
    expect(r.status).toBe('COMPLETED')
    expect(client.post).toHaveBeenCalledWith('/tasks/7/status', { status: 'COMPLETED' })
  })

  it('stats: GET /tasks/stats 返回 4 个数字', async () => {
    vi.mocked(client.get).mockResolvedValueOnce({
      familyPendingCount: 5,
      minePendingCount: 2,
      familyOverdueCount: 3,
      mineOverdueCount: 1
    })
    const r = await tasksApi.stats()
    expect(r.familyPendingCount).toBe(5)
    expect(r.minePendingCount).toBe(2)
    expect(r.familyOverdueCount).toBe(3)
    expect(r.mineOverdueCount).toBe(1)
    expect(client.get).toHaveBeenCalledWith('/tasks/stats')
  })
})
