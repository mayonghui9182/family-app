import { describe, it, expect, beforeEach, vi } from 'vitest'
import client from '../../api/client'
import { calendarApi } from '../../api/calendar'

vi.mock('../../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }
}))

describe('calendarApi mock 层', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('list: GET /calendar/events', async () => {
    vi.mocked(client.get).mockResolvedValueOnce([])
    const r = await calendarApi.list()
    expect(r).toEqual([])
    expect(client.get).toHaveBeenCalledWith('/calendar/events')
  })

  it('list(includeInactive=true): GET /calendar/events 带 includeInactive 查询参数', async () => {
    vi.mocked(client.get).mockResolvedValueOnce([])
    await calendarApi.list(true)
    expect(client.get).toHaveBeenCalledWith('/calendar/events', { params: { includeInactive: true } })
  })

  it('create: POST /calendar/events', async () => {
    const payload = { title: '周会', eventTime: '10:00', recurrence: '{"freq":"weekly","weekdays":[2]}' }
    vi.mocked(client.post).mockResolvedValueOnce({ id: 1 })
    const r = await calendarApi.create(payload)
    expect(r.id).toBe(1)
    expect(client.post).toHaveBeenCalledWith('/calendar/events', payload)
  })

  it('get: GET /calendar/events/:id', async () => {
    vi.mocked(client.get).mockResolvedValueOnce({ id: 9, title: '复查血压' })
    const r = await calendarApi.get(9)
    expect(r.title).toBe('复查血压')
    expect(client.get).toHaveBeenCalledWith('/calendar/events/9')
  })

  it('update: PUT /calendar/events/:id', async () => {
    const payload = { title: '改时间' , eventTime: '15:00' }
    vi.mocked(client.put).mockResolvedValueOnce({ id: 3, eventTime: '15:00' })
    const r = await calendarApi.update(3, payload)
    expect(r.eventTime).toBe('15:00')
    expect(client.put).toHaveBeenCalledWith('/calendar/events/3', payload)
  })

  it('today: GET /calendar/today', async () => {
    vi.mocked(client.get).mockResolvedValueOnce([{ title: '周会', eventTime: '10:00' }])
    const r = await calendarApi.today()
    expect(r).toHaveLength(1)
    expect(client.get).toHaveBeenCalledWith('/calendar/today')
  })

  it('del: DELETE /calendar/events/:id', async () => {
    vi.mocked(client.delete).mockResolvedValueOnce(true)
    const r = await calendarApi.del(3)
    expect(r).toBe(true)
    expect(client.delete).toHaveBeenCalledWith('/calendar/events/3')
  })
})
