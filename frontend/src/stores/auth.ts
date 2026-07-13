import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from '@/api'
import type { LoginResult } from '@/types'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('token') || '')
  const userId = ref<string | number>('')
  const familyId = ref<string | number>('')
  const userName = ref('')
  const familyName = ref('')
  const role = ref('')
  const loading = ref(false)

  const isLoggedIn = computed(() => !!token.value)

  async function createFamily(familyName: string, userName: string) {
    loading.value = true
    try {
      const data = await authApi.createFamily({ familyName, userName })
      setAuthInfo(data)
      return data
    } finally {
      loading.value = false
    }
  }

  async function joinFamily(inviteCode: string, userName: string) {
    loading.value = true
    try {
      const data = await authApi.joinFamily({ inviteCode, userName })
      setAuthInfo(data)
      return data
    } finally {
      loading.value = false
    }
  }

  function setAuthInfo(data: LoginResult) {
    token.value = data.token
    userId.value = data.userId
    familyId.value = data.familyId
    userName.value = data.userName
    familyName.value = data.familyName
    role.value = data.role
    localStorage.setItem('token', data.token)
    localStorage.setItem('userInfo', JSON.stringify(data))
  }

  function logout() {
    token.value = ''
    userId.value = ''
    familyId.value = ''
    userName.value = ''
    familyName.value = ''
    role.value = ''
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
  }

  return {
    token,
    userId,
    familyId,
    userName,
    familyName,
    role,
    loading,
    isLoggedIn,
    createFamily,
    joinFamily,
    logout,
  }
})
