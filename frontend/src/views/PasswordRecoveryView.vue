<template>
  <main class="recovery-page">
    <el-card>
      <h1>{{ resetting ? '设置新密码' : '找回密码' }}</h1>
      <template v-if="done">
        <p role="status">{{ resetting ? '密码已更新，请使用新密码登录。' : '申请已受理。如果此邮箱已注册，请留意重置邮件（链接15分钟内有效）。未收到时请检查垃圾邮件，稍后再试。' }}</p>
      </template>
      <template v-else-if="resetting && !token">
        <p role="alert">链接不完整或已被清除，请重新申请找回密码。</p>
        <RouterLink to="/forgot-password">重新申请</RouterLink>
      </template>
      <el-form v-else label-position="top" :disabled="busy" @submit.prevent="submit">
        <template v-if="!resetting">
          <p>输入注册时填写的邮箱，我们会向该邮箱发送重置链接。</p>
          <el-form-item label="注册邮箱">
            <el-input v-model="email" type="email" autocomplete="email" placeholder="请输入注册邮箱" maxlength="120" size="large" />
          </el-form-item>
          <p class="hint">仅用手机号注册，或已无法访问此邮箱？目前暂不支持这种找回方式。</p>
        </template>
        <template v-else>
          <el-form-item label="新密码（8–64个字符）">
            <el-input v-model="password" type="password" autocomplete="new-password" show-password placeholder="请输入新密码" maxlength="64" size="large" />
          </el-form-item>
          <el-form-item label="再次输入新密码">
            <el-input v-model="repeated" type="password" autocomplete="new-password" show-password placeholder="请再次输入新密码" maxlength="64" size="large" />
          </el-form-item>
        </template>
        <p v-if="error" role="alert">{{ error }}</p>
        <el-button native-type="submit" type="primary" size="large" :loading="busy" :disabled="busy">
          {{ resetting ? '确认新密码' : '申请重置链接' }}
        </el-button>
      </el-form>
      <RouterLink to="/login">返回登录</RouterLink>
      <RouterLink v-if="resetting && error" to="/forgot-password">重新申请链接</RouterLink>
    </el-card>
  </main>
</template>
<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import client from '../api/client'
import { useAuthStore } from '../stores/auth'
import { useFamilyStore } from '../stores/family'
const route = useRoute()
const resetting = computed(() => route.path === '/reset-password')
const token = ref('')
const email = ref('')
const password = ref('')
const repeated = ref('')
const error = ref('')
const busy = ref(false)
const done = ref(false)
const auth = useAuthStore()
const family = useFamilyStore()
let generation = 0
onBeforeUnmount(() => { generation += 1 })
watch(() => [route.path, route.hash], () => {
  generation += 1; busy.value = false
  done.value = false; error.value = ''; password.value = ''; repeated.value = ''
  token.value = resetting.value ? new URLSearchParams(window.location.hash.slice(1)).get('token') || '' : ''
  // 凭证仅留在页面内存中，禁止持久化；刷新后需重新打开邮件链接。
  if (resetting.value && window.location.hash) window.history.replaceState(window.history.state, '', window.location.pathname)
}, { immediate: true })
async function submit() {
  if (busy.value) return
  error.value = ''
  if (!resetting.value && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.value.trim())) {
    error.value = '请填写有效的注册邮箱。'; return
  }
  if (resetting.value && (password.value.length < 8 || password.value.length > 64 || password.value !== repeated.value)) {
    error.value = '密码需要8到64个字符，两次输入必须一致。'; return
  }
  const current = generation
  busy.value = true
  try {
    if (resetting.value) {
      await client.post('/auth/password-reset/confirm', { token: token.value, password: password.value })
      auth.logout(); family.clear(); token.value = ''; password.value = ''; repeated.value = ''
    } else await client.post('/auth/password-reset/request', { email: email.value.trim() })
    if (current === generation) done.value = true
  } catch (e) {
    if (current !== generation) return
    error.value = e?.response?.data?.msg || e?.message || '操作未完成，请稍后重试。'
  } finally { if (current === generation) busy.value = false }
}
</script>
<style scoped>
.recovery-page { max-width: 480px; margin: 32px auto; padding: 16px; font-size: 18px; line-height: 1.7; color: #1f2937; }
.recovery-page h1 { font-size: 28px; }
.recovery-page a { display: block; padding: 12px 0; color: #1d4ed8; }
.recovery-page :deep(.el-button) { min-height: 48px; width: 100%; font-size: 18px; }
.recovery-page :deep(.el-input__inner) { min-height: 48px; font-size: 18px; }
.recovery-page :deep(.el-form-item__label) { font-size: 18px; }
.hint { color: #4b5563; }
[role="alert"] { color: #b91c1c; }
</style>
