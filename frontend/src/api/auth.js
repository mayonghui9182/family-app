import client from './client'

export const authApi = {
  register: (payload) => client.post('/auth/register', payload),
  login:    (payload) => client.post('/auth/login', payload),
  logout:   ()        => client.post('/auth/logout'),
  me:       ()        => client.get('/auth/me'),
  // 修改密码：旧密码二次确认 + 新密码
  changePassword: (payload) => client.post('/auth/change-password', payload),
  // Change 11：微信绑定页提交一次性票据 + 邮箱/密码
  wechatBind: (payload) => client.post('/auth/wechat-bind', payload)
}

export default authApi