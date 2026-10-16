import { describe, it, expect, beforeEach, vi } from 'vitest'
import client from '../../api/client'
import { categoryApi } from '../../api/categories'

vi.mock('../../api/client', () => ({
  default: {
    post: vi.fn(),
    get: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  }
}))

describe('categoryApi mock 层', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('list: GET /categories', async () => {
    vi.mocked(client.get).mockResolvedValueOnce([])
    const r = await categoryApi.list()
    expect(r).toEqual([])
    expect(client.get).toHaveBeenCalledWith('/categories')
  })

  it('create: POST /categories', async () => {
    const payload = { name: '饮食' }
    vi.mocked(client.post).mockResolvedValueOnce({ id: 1, name: '饮食' })
    const r = await categoryApi.create(payload)
    expect(r.name).toBe('饮食')
    expect(client.post).toHaveBeenCalledWith('/categories', payload)
  })

  it('update: PUT /categories/:id', async () => {
    const payload = { name: '交通' }
    vi.mocked(client.put).mockResolvedValueOnce({ id: 2, name: '交通' })
    const r = await categoryApi.update(2, payload)
    expect(r.name).toBe('交通')
    expect(client.put).toHaveBeenCalledWith('/categories/2', payload)
  })

  it('del: DELETE /categories/:id，有 mergeTo 时 params.mergeTo 正确', async () => {
    vi.mocked(client.delete).mockResolvedValueOnce(true)
    await categoryApi.del(3, 7)
    expect(client.delete).toHaveBeenCalledWith('/categories/3', { params: { mergeTo: 7 } })
  })

  it('del: DELETE /categories/:id，无 mergeTo 时 params 为空对象', async () => {
    vi.mocked(client.delete).mockResolvedValueOnce(true)
    await categoryApi.del(3)
    expect(client.delete).toHaveBeenCalledWith('/categories/3', {})
  })

  it('merge: POST /categories/:id/merge，body 字段为 { mergeTo: id }', async () => {
    vi.mocked(client.post).mockResolvedValueOnce(null)
    await categoryApi.merge(10, 20)
    expect(client.post).toHaveBeenCalledWith('/categories/10/merge', { mergeTo: 20 })
  })
})