import { defineStore } from 'pinia'

function parseJSON(value, fallback) {
  if (!value) return fallback
  try {
    return JSON.parse(value)
  } catch (e) {
    return fallback
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('accessToken') || '',
    userInfo: parseJSON(localStorage.getItem('userInfo'), null),
    families: parseJSON(localStorage.getItem('families'), [])
  }),
  getters: {
    isLoggedIn: (state) => !!state.token
  },
  actions: {
    setToken(token) {
      this.token = token || ''
      if (token) {
        localStorage.setItem('accessToken', token)
      } else {
        localStorage.removeItem('accessToken')
      }
    },
    setUserInfo(info) {
      this.userInfo = info || null
      if (info) {
        localStorage.setItem('userInfo', JSON.stringify(info))
      } else {
        localStorage.removeItem('userInfo')
      }
    },
    setFamilies(families) {
      this.families = families || []
      localStorage.setItem('families', JSON.stringify(this.families))
    },
    logout() {
      this.token = ''
      this.userInfo = null
      this.families = []
      localStorage.removeItem('accessToken')
      localStorage.removeItem('userInfo')
      localStorage.removeItem('families')
    }
  }
})