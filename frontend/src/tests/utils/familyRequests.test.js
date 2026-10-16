import { it, expect } from 'vitest'
import { trackFamilyRequest, finishFamilyRequest, cancelFamilyRequests } from '@/api/familyRequests'
import { safeNext } from '@/utils/safeNext'
it('家庭切换取消请求，往返切换也拒绝旧响应', () => {
  const old = {}; trackFamilyRequest(old)
  cancelFamilyRequests(); cancelFamilyRequests()
  const current = {}; trackFamilyRequest(current)
  expect(old.signal.aborted).toBe(true)
  expect(finishFamilyRequest(old)).toBe(false)
  expect(finishFamilyRequest(current)).toBe(true)
})
it('深链只恢复支持的站内路径', () => {
  expect(safeNext('/agent/draft/9')).toBe('/agent/draft/9')
  for (const url of ['https://evil.test', '//evil.test', '/agent/draft/9/../evil', '/agent/draft/%39', '/evil/chat', '/chat\\evil']) {
    expect(safeNext(url)).toBe('/')
  }
})
