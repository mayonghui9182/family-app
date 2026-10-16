<template>
  <div class="wx-cb-page">
    <div class="wx-cb-card">
      <div class="wx-cb-icon">{{ ready ? '✅' : '👋' }}</div>
      <h1 class="wx-cb-title">{{ ready ? '登录成功，正在进入…' : '请从公众号菜单进入' }}</h1>
      <p v-if="!ready" class="wx-cb-subtitle">
        这个页面需要从微信公众号菜单打开。请关闭当前页面，重新点击菜单中的「打开家庭应用」。
      </p>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useFamilyStore } from '../stores/family'
import { familyApi } from '../api/family'
import { safeNext } from '../utils/safeNext'
import { authApi } from '../api/auth'

const router = useRouter()
const authStore = useAuthStore()
const familyStore = useFamilyStore()
const ready = ref(false)

onMounted(async () => {
  // token 由 wechat-service 302 放在 URL fragment（#token=...），不进服务器日志/Referer
  const hash = window.location.hash || ''
  const params = new URLSearchParams(hash.startsWith('#') ? hash.slice(1) : hash)
  const token = params.get('token')
  const nextPath = safeNext(params.get('next'))
  if (token) {
    authStore.setToken(token)
    // 立即清掉地址栏中的 token（replaceState 不留历史、不刷新页面）
    window.history.replaceState(null, '', window.location.pathname)
    ready.value = true
    // 新会话（微信内置浏览器）无 currentFamilyId：先拉家庭列表并自动选第一个，
    // 再进内页，避免业务请求缺 X-Family-Id 失败；失败不阻断，AppHeader 会兜底
    try {
      const [list, me] = await Promise.all([familyApi.list(), authApi.me()])
      authStore.setUserInfo({ userId: me.userId, nickname: me.nickname })
      authStore.setFamilies(list || [])
      if (list && list.length > 0 && !familyStore.currentFamilyId) {
        familyStore.setCurrent(list[0])
      }
    } catch (e) { /* 忽略，内页 AppHeader 兜底 */ }
    router.replace(nextPath)
  }
})
</script>

<style scoped>
.wx-cb-page {
  min-height: 100vh;
  background: #f5f7fa;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  box-sizing: border-box;
}
.wx-cb-card {
  width: 100%;
  max-width: 420px;
  background: #fff;
  border-radius: 16px;
  padding: 32px 24px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.06);
  text-align: center;
}
.wx-cb-icon { font-size: 48px; }
.wx-cb-title { font-size: 20px; margin: 12px 0 8px; color: #303133; }
.wx-cb-subtitle { font-size: 14px; color: #909399; line-height: 1.7; }
</style>
