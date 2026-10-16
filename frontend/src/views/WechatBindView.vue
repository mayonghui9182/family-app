<template>
  <div class="wx-bind-page">
    <div class="wx-bind-card">
      <div class="wx-bind-icon">🔗</div>
      <h1 class="wx-bind-title">绑定微信账号</h1>
      <p class="wx-bind-subtitle">输入您登录家庭应用的邮箱和密码，绑定后下次点公众号菜单就能直接进入</p>

      <el-alert
        v-if="ticketInvalid"
        type="error"
        :closable="false"
        class="wx-bind-alert"
        title="绑定链接已失效"
        description="请关闭页面，重新从微信公众号菜单进入绑定。"
        show-icon
      />

      <template v-else>
        <el-input
          v-model="email"
          class="wx-bind-input"
          size="large"
          placeholder="请输入邮箱"
          :disabled="loading"
          data-testid="wx-email"
        />
        <el-input
          v-model="password"
          class="wx-bind-input"
          size="large"
          type="password"
          show-password
          placeholder="请输入密码"
          :disabled="loading"
          data-testid="wx-password"
          @keyup.enter="onSubmit"
        />

        <el-alert
          v-if="errorMsg"
          type="error"
          :closable="false"
          class="wx-bind-alert"
          :title="errorMsg"
          show-icon
        />

        <el-button
          type="primary"
          size="large"
          class="wx-bind-submit"
          :loading="loading"
          data-testid="wx-submit"
          @click="onSubmit"
        >
          确认绑定并进入
        </el-button>
      </template>
    </div>
  </div>
</template>

<script setup>
import { safeNext } from '../utils/safeNext'
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '../api/auth'
import { useAuthStore } from '../stores/auth'
import { useFamilyStore } from '../stores/family'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const familyStore = useFamilyStore()

const email = ref('')
const password = ref('')
const loading = ref(false)
const errorMsg = ref('')

// 一次性票据：由 wechat-service OAuth 回调 302 带来（?ticket=xxx）
const ticket = computed(() => String(route.query.ticket || ''))
const ticketInvalid = computed(() => !ticket.value)

async function onSubmit() {
  if (!email.value.trim()) { ElMessage.warning('请输入邮箱'); return }
  if (!password.value) { ElMessage.warning('请输入密码'); return }
  loading.value = true
  errorMsg.value = ''
  try {
    const data = await authApi.wechatBind({
      ticket: ticket.value,
      email: email.value.trim(),
      password: password.value
    })
    authStore.setToken(data.accessToken || '')
    authStore.setUserInfo({ nickname: data.nickname, userId: data.userId })
    if (Array.isArray(data.families)) authStore.setFamilies(data.families)
    // 绑定后首次进入：自动选第一个家庭，避免业务请求缺 X-Family-Id
    if (data.families && data.families.length > 0 && !familyStore.currentFamilyId) {
      familyStore.setCurrent(data.families[0])
    }
    ElMessage.success('绑定成功，已为您登录')
    router.replace(safeNext(route.query.next))
  } catch (e) {
    // backend 业务码：40113 密码错 / 40116 票据失效；msg 已是中文老年友好文案
    errorMsg.value = e?.response?.data?.msg || '绑定失败，请稍后再试'
    if (e?.response?.data?.code === 40116) {
      // 票据失效：整页降级，引导从菜单重进
      router.replace({ query: {} })
    }
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.wx-bind-page {
  min-height: 100vh;
  background: #f5f7fa;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  box-sizing: border-box;
}
.wx-bind-card {
  width: 100%;
  max-width: 420px;
  background: #fff;
  border-radius: 16px;
  padding: 32px 24px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.06);
  text-align: center;
}
.wx-bind-icon { font-size: 48px; }
.wx-bind-title { font-size: 22px; margin: 12px 0 8px; color: #303133; }
.wx-bind-subtitle {
  font-size: 14px;
  color: #909399;
  line-height: 1.7;
  margin: 0 0 24px;
  text-align: left;
}
.wx-bind-input { margin-bottom: 16px; }
.wx-bind-submit { width: 100%; height: 48px; font-size: 18px; margin-top: 8px; }
.wx-bind-alert { margin: 0 0 16px; text-align: left; }
</style>
