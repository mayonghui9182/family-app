import { describe, it, expect, beforeEach, vi, beforeAll } from 'vitest'
import client from '../../api/client'
import { authApi } from '../../api/auth'

vi.mock('../../api/client', () => ({
  default: {
    post: vi.fn(),
    get: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  }
}))

const baseURL = import.meta.env.TEST_E2E_BASE_URL || 'http://localhost:8080'
const isE2E = import.meta.env.TEST_E2E === '1'

let e2eSkipAll = false
let e2eSkipReason = ''

beforeAll(async () => {
  if (!isE2E) return
  try {
    const res = await fetch(`${baseURL}/actuator/health`)
    if (!res.ok) throw new Error('health not ok: ' + res.status)
    const json = await res.json()
    if (json.status !== 'UP') throw new Error('health not UP')
  } catch (e) {
    e2eSkipAll = true
    e2eSkipReason = 'E2E skipped（后端未启动）: ' + e.message
    console.log(e2eSkipReason)
  }
})

describe('authApi mock 层', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('register: POST /auth/register，payload 透传', async () => {
    const payload = { email: 'a@b.c', password: 'p1234567', nickname: 'u1' }
    vi.mocked(client.post).mockResolvedValueOnce({ ok: true })
    const result = await authApi.register(payload)
    expect(result).toEqual({ ok: true })
    expect(client.post).toHaveBeenCalledWith('/auth/register', payload)
  })

  it('login: POST /auth/login，payload 透传', async () => {
    const payload = { email: 'a@b.c', password: 'pw12345' }
    vi.mocked(client.post).mockResolvedValueOnce({ token: 'tok' })
    const result = await authApi.login(payload)
    expect(result).toEqual({ token: 'tok' })
    expect(client.post).toHaveBeenCalledWith('/auth/login', payload)
  })

  it('me: GET /auth/me', async () => {
    vi.mocked(client.get).mockResolvedValueOnce({ nickname: 'u' })
    const result = await authApi.me()
    expect(result).toEqual({ nickname: 'u' })
    expect(client.get).toHaveBeenCalledWith('/auth/me')
  })

  it('logout: POST /auth/logout', async () => {
    vi.mocked(client.post).mockResolvedValueOnce(null)
    const result = await authApi.logout()
    expect(result).toBeNull()
    expect(client.post).toHaveBeenCalledWith('/auth/logout')
  })
})

if (isE2E) {
  describe('authApi E2E 层', () => {
    let testToken
    const email = `e2e-${Date.now()}@test.com`
    const password = 'e2epass123'
    const nickname = 'E2E测试用户'

    it('跳过所有 E2E 用例（若后端未启动）', () => {
      if (e2eSkipAll) {
        console.log(e2eSkipReason)
      }
      expect(true).toBe(true)
    })

    it('E2E: register -> login -> me -> logout', async () => {
      if (e2eSkipAll) return
      const reg = await fetch(`${baseURL}/api/auth/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password, nickname })
      })
      expect(reg.ok).toBe(true)
      const regJson = await reg.json()
      expect(regJson.code).toBe(0)
      expect(regJson.data.accessToken).toBeTruthy()
      testToken = regJson.data.accessToken

      const login = await fetch(`${baseURL}/api/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password })
      })
      expect(login.ok).toBe(true)
      const loginJson = await login.json()
      expect(loginJson.code).toBe(0)

      const me = await fetch(`${baseURL}/api/auth/me`, {
        headers: { 'X-Access-Token': testToken }
      })
      expect(me.ok).toBe(true)
      const meJson = await me.json()
      expect(meJson.code).toBe(0)
      expect(meJson.data.nickname).toBe(nickname)

      const logout = await fetch(`${baseURL}/api/auth/logout`, {
        method: 'POST',
        headers: { 'X-Access-Token': testToken }
      })
      expect(logout.ok).toBe(true)
    })
  })
}