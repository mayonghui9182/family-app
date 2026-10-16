import { describe, it, expect, beforeEach, vi } from 'vitest'
import client from '../../api/client'
import { familyApi } from '../../api/family'

vi.mock('../../api/client', () => ({
  default: {
    post: vi.fn(),
    get: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  }
}))

describe('familyApi mock 层', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('create: POST /families', async () => {
    const payload = { name: '家1' }
    vi.mocked(client.post).mockResolvedValueOnce({ id: 1 })
    const r = await familyApi.create(payload)
    expect(r).toEqual({ id: 1 })
    expect(client.post).toHaveBeenCalledWith('/families', payload)
  })

  it('list: GET /families', async () => {
    vi.mocked(client.get).mockResolvedValueOnce([])
    const r = await familyApi.list()
    expect(r).toEqual([])
    expect(client.get).toHaveBeenCalledWith('/families')
  })

  it('getDetail: GET /families/:id', async () => {
    vi.mocked(client.get).mockResolvedValueOnce({ id: 5, name: '家' })
    const r = await familyApi.getDetail(5)
    expect(r.id).toBe(5)
    expect(client.get).toHaveBeenCalledWith('/families/5')
  })

  it('update: PUT /families/:id', async () => {
    const payload = { name: '新家' }
    vi.mocked(client.put).mockResolvedValueOnce({ id: 3, name: '新家' })
    const r = await familyApi.update(3, payload)
    expect(r.name).toBe('新家')
    expect(client.put).toHaveBeenCalledWith('/families/3', payload)
  })

  it('leave: POST /families/:id/leave', async () => {
    vi.mocked(client.post).mockResolvedValueOnce(null)
    const r = await familyApi.leave(4)
    expect(r).toBeNull()
    expect(client.post).toHaveBeenCalledWith('/families/4/leave')
  })

  it('transferOwner: payload.targetUserId 优先，fallback newOwnerId', async () => {
    vi.mocked(client.post).mockResolvedValueOnce(null)
    await familyApi.transferOwner(5, { targetUserId: 10 })
    expect(client.post).toHaveBeenCalledWith('/families/5/transfer-owner', { targetUserId: 10 })

    vi.clearAllMocks()
    await familyApi.transferOwner(5, { newOwnerId: 20 })
    expect(client.post).toHaveBeenCalledWith('/families/5/transfer-owner', { targetUserId: 20 })
  })

  it('changeRole: POST /families/:id/members/:userId', async () => {
    const payload = { role: 'CHILD' }
    vi.mocked(client.post).mockResolvedValueOnce(null)
    const r = await familyApi.changeRole(1, 2, payload)
    expect(r).toBeNull()
    expect(client.post).toHaveBeenCalledWith('/families/1/members/2', payload)
  })

  it('kick: DELETE /families/:id/members/:userId', async () => {
    vi.mocked(client.delete).mockResolvedValueOnce(null)
    const r = await familyApi.kick(1, 2)
    expect(r).toBeNull()
    expect(client.delete).toHaveBeenCalledWith('/families/1/members/2')
  })

  it('remove: DELETE /families/:id', async () => {
    vi.mocked(client.delete).mockResolvedValueOnce(null)
    const r = await familyApi.remove(7)
    expect(r).toBeNull()
    expect(client.delete).toHaveBeenCalledWith('/families/7')
  })
})