import axios from 'axios'
import { trackFamilyRequest, finishFamilyRequest } from './familyRequests.js'
import { ElMessage } from 'element-plus'

/**
 * 全局错误提示（老人友好）：5 秒自动消失 + 显示手动关闭按钮。
 * 所有 HTTP 错误统一走这里，避免散落在各调用点的 ElMessage.error 时长不一致。
 */
function showError(msg) {
  ElMessage({
    type: 'error',
    message: msg,
    duration: 5000,
    showClose: true,
  })
}

const client = axios.create({
  baseURL: '/api',
  timeout: 15000
})

function isAuthUrl(url) {
  return typeof url === 'string' && url.indexOf('/auth/') === 0
}

/**
 * 需要 X-Family-Id 头的端点：与后端 FamilyContextInterceptor 对齐。
 * /families/** 是身份维度（路径自带家庭 id，列表页本身就是选家庭的地方）、
 * /agent/v1/config 是 D-7 公开端点，均不拦截；其余业务端点缺家庭头时仅提示并 reject，
 * 不做整页跳转（避免与路由守卫互相整页跳转形成 /families ↔ /login 死循环）。
 */
function needFamilyHeader(url) {
  if (typeof url !== 'string') return false
  if (isAuthUrl(url)) return false
  if (url.startsWith('/daily/')) return false
  if (url.startsWith('/families')) return false
  if (url.startsWith('/agent/v1/config')) return false
  return true
}

client.interceptors.request.use((config) => {
  if (!['/auth/login', '/auth/register', '/auth/wechat-bind', '/auth/password-reset/request', '/auth/password-reset/confirm'].includes(config.url)) {
    const token = localStorage.getItem('accessToken')
    if (token) {
      config.headers['X-Access-Token'] = token
    }
  }
  if (needFamilyHeader(config.url)) {
    const familyId = localStorage.getItem('currentFamilyId')
    if (familyId) {
      config.headers['X-Family-Id'] = familyId
      trackFamilyRequest(config)
    } else {
      // 仅提示并 reject，不做整页跳转：正常流程由 AppHeader/微信回调自动选家庭兜底，
      // 整页跳转会与路由守卫互相触发导致死循环
      showError('请先选择一个家庭')
      return Promise.reject(new Error('未选择家庭'))
    }
  }
  return config
})

client.interceptors.response.use(
  (resp) => {
    if (!finishFamilyRequest(resp.config)) {
      throw new axios.CanceledError('家庭已切换')
    }
    const body = resp.data || {}
    const { code, msg, data } = body
    if (code !== 0) {
      // ====== 显式特殊 code 映射（Hard Constraints 后端 ResultCode 中文兜底）======
      if (code === 50310) {
        // AgentProxyController C-25~C-34 guardAiEnabled() 返回：AI拆解功能管理员关闭
        showError(msg || 'AI拆解功能已关闭，请联系家庭管理员')
      } else if (code === 50311 || code === 50312 || code === 50313) {
        // DecomposeSessionService 终态守卫/签名校验错误（如重复 confirm/cancel）
        showError(msg || 'AI 拆解状态异常，请刷新页面后重试')
      } else {
        showError(msg || '请求失败')
      }
      const err = new Error(msg || '请求失败')
      err.code = code
      err.body = body
      throw err
    }
    return data
  },
  (err) => {
    const active = finishFamilyRequest(err?.config)
    if (!active || err?.code === 'ERR_CANCELED') throw err
    const status = err && err.response ? err.response.status : undefined
    const respData = (err && err.response && err.response.data) || {}
    const msg = respData.msg || respData.message
    // HTTP 503 且 respData.code 是已知 AI 错误时：优先显示后端 Result 中文 msg
    const specialAiCode = respData && (respData.code === 50310 || respData.code === 50311 || respData.code === 50312 || respData.code === 50313)
    if (specialAiCode) {
      if (respData.code === 50310) {
        showError(msg || 'AI拆解功能已关闭，请联系家庭管理员')
      } else {
        showError(msg || 'AI 拆解状态异常，请刷新页面后重试')
      }
    } else if (status === 401) {
      localStorage.removeItem('accessToken')
      showError('登录已过期，请重新登录')
      window.location.href = '/login'
    } else if (status === 403) {
      showError(msg || '没有权限执行此操作')
    } else if (err && (err.code === 'ECONNABORTED' || err.code === 'ETIMEDOUT')) {
      showError('请求超时，请检查网络后重试')
    } else if (!err || !err.response) {
      showError('网络不好，请稍后再试')
    } else {
      showError(msg || '请求失败，请稍后再试')
    }
    throw err
  }
)

export default client
