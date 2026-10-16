import { describe, it, expect, beforeEach, vi } from 'vitest'
import client from '../../api/client'
import { inviteApi } from '../../api/invite'

vi.mock('../../api/client', () => ({
  default: {
    post: vi.fn(),
    get: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  }
}))

describe('inviteApi mock 层', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('create: POST /families/:familyId/invites', async () => {
    const payload = { maxUses: 10, ttlDays: 7 }
    vi.mocked(client.post).mockResolvedValueOnce({ code: 'ABC123' })
    const r = await inviteApi.create(5, payload)
    expect(r.code).toBe('ABC123')
    expect(client.post).toHaveBeenCalledWith('/families/5/invites', payload)
  })

  it('list: GET /families/:familyId/invites，有 status 时 params.status 正确', async () => {
    vi.mocked(client.get).mockResolvedValueOnce([])
    const r = await inviteApi.list(5, 'ACTIVE')
    expect(r).toEqual([])
    expect(client.get).toHaveBeenCalledWith('/families/5/invites', { params: { status: 'ACTIVE' } })
  })

  it('list: GET /families/:familyId/invites，无 status 时 params 为空对象', async () => {
    vi.mocked(client.get).mockResolvedValueOnce([])
    await inviteApi.list(5)
    expect(client.get).toHaveBeenCalledWith('/families/5/invites', { params: {} })
  })

  it('cancel: DELETE /families/:familyId/invites/:inviteId', async () => {
    vi.mocked(client.delete).mockResolvedValueOnce(null)
    const r = await inviteApi.cancel(5, 99)
    expect(r).toBeNull()
    expect(client.delete).toHaveBeenCalledWith('/families/5/invites/99')
  })

  it('accept: POST /invites/accept，payload 透传', async () => {
    const payload = { code: 'XYZ789' }
    vi.mocked(client.post).mockResolvedValueOnce(null)
    const r = await inviteApi.accept(payload)
    expect(r).toBeNull()
    expect(client.post).toHaveBeenCalledWith('/invites/accept', payload)
  })
})