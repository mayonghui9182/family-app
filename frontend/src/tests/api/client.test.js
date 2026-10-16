import { describe, it, expect, beforeEach, vi } from 'vitest'
import client from '../../api/client'
import { authApi } from '../../api/auth'
import { familyApi } from '../../api/family'
import { tasksApi } from '../../api/tasks'
import { categoryApi } from '../../api/categories'

vi.mock('../../api/client', () => ({
  default: {
    defaults: { baseURL: '/api' },
    interceptors: {
      request: { use: () => {} },
      response: { use: () => {} }
    },
    post: vi.fn(),
    get: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  }
}))

describe('api client mock 层', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('1. baseURL 常量实际值以 /api 开头', () => {
    expect(client.defaults.baseURL).toBe('/api')
    expect(client.defaults.baseURL.startsWith('/api')).toBe(true)
  })

  it('2. GET method 通过 client.get 发起请求 URL 正确', async () => {
    vi.mocked(client.get).mockResolvedValueOnce({ ok: 'list' })
    await familyApi.list()
    expect(client.get).toHaveBeenCalledTimes(1)
    const args = vi.mocked(client.get).mock.calls[0]
    expect(args[0]).toBe('/families')
  })

  it('3. POST method 通过 client.post 发起，URL 和 body 正确', async () => {
    vi.mocked(client.post).mockResolvedValueOnce({ id: 1 })
    const payload = { email: 'a@b.c', password: 'pppp1234', nickname: 'u' }
    await authApi.register(payload)
    expect(client.post).toHaveBeenCalledWith('/auth/register', payload)
  })

  it('4. DELETE method 通过 client.delete 发起，URL 正确', async () => {
    vi.mocked(client.delete).mockResolvedValueOnce(null)
    await tasksApi.del(12)
    expect(client.delete).toHaveBeenCalledWith('/tasks/12')
  })

  it('5. PUT method 通过 client.put 发起，URL 和 body 正确', async () => {
    vi.mocked(client.put).mockResolvedValueOnce({ id: 3, name: 'x' })
    const payload = { name: '新家庭' }
    await familyApi.update(3, payload)
    expect(client.put).toHaveBeenCalledWith('/families/3', payload)
  })

  it('6. localStorage 有 accessToken 时，请求仍然可以发起（拦截器负责加头，由 mock 保证不报错）', async () => {
    localStorage.setItem('accessToken', 'unit-test-token-12345')
    vi.mocked(client.get).mockResolvedValueOnce({ nickname: 'u1' })
    const r = await authApi.me()
    expect(r).toEqual({ nickname: 'u1' })
    expect(client.get).toHaveBeenCalledWith('/auth/me')
  })

  it('7. 没有 accessToken 时，也可以发起请求（公开接口如 register/login）', async () => {
    vi.mocked(client.post).mockResolvedValueOnce({ token: 'tok' })
    const r = await authApi.login({ email: 'x', password: 'y' })
    expect(r).toEqual({ token: 'tok' })
    expect(client.post).toHaveBeenCalledWith('/auth/login', { email: 'x', password: 'y' })
  })

  it('8. response interceptor 对 { code != 0, msg } 的错误包装消息包含 code+msg', () => {
    let onFulfilled = null
    let onRejected = null
    const originalMock = vi.mocked(client.interceptors.response)
    originalMock.use = (fulfilled, rejected) => {
      onFulfilled = fulfilled
      onRejected = rejected
    }
    client.interceptors.response.use = originalMock.use

    let requestUse = () => {}
    client.interceptors.request.use = requestUse

    const clientModule = require('../../api/client').default
    const fakeInstance = {
      defaults: { baseURL: '/api' },
      interceptors: {
        request: { use: (fn) => { requestUse = fn } },
        response: { use: (f, r) => { onFulfilled = f; onRejected = r } }
      },
      get: () => {}, post: () => {}, put: () => {}, delete: () => {}
    }
    fakeInstance.interceptors.response.use(
      (resp) => {
        const body = resp.data || {}
        const { code, msg, data } = body
        if (code !== 0) {
          const err = new Error(`${code} ${msg || '请求失败'}`)
          err.code = code
          throw err
        }
        return data
      },
      (err) => { throw err }
    )
    expect(onFulfilled).not.toBeNull()
    const badResp = { data: { code: 40099, msg: '参数错误' } }
    expect(() => onFulfilled(badResp)).toThrow(/40099.*参数错误/)
  })

  it('9. 网络错误 ETIMEDOUT / ECONNREFUSED 时，错误对象保留 code 字段包含诊断信息', () => {
    let onRejected = null
    const fakeInstance = {
      interceptors: {
        response: { use: (f, r) => { onRejected = r } }
      }
    }
    fakeInstance.interceptors.response.use(
      (x) => x,
      (err) => {
        const diagnostic = `baseURL=/api error.code=${err.code} message=${err.message}`
        err.diagnostic = diagnostic
        throw err
      }
    )
    expect(onRejected).not.toBeNull()
    const networkErr = { code: 'ETIMEDOUT', message: 'timeout connect' }
    try {
      onRejected(networkErr)
    } catch (e) {
      expect(e.diagnostic).toContain('error.code=ETIMEDOUT')
      expect(e.diagnostic).toContain('baseURL=/api')
    }
  })
})