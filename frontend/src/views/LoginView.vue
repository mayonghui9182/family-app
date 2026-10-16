<template>
  <div class="login-page">
    <el-card class="login-card" role="region" aria-label="家庭助手登录或注册页面">
      <h1 class="login-title">🏠 家庭助手</h1>
      <h2 class="sr-only">请选择登录或注册</h2>
      <el-tabs v-model="activeTab" stretch>
        <el-tab-pane label="登录" name="login">
          <el-form :model="loginForm" label-position="top" @submit.prevent="onLogin">
            <el-form-item label="账号">
              <el-input ref="loginIdRef" v-model="loginForm.identity" size="large" clearable placeholder="邮箱或手机号">
                <template #prefix><span class="prefix-emoji" aria-hidden="true" tabindex="-1">👤</span></template>
              </el-input>
              <div class="field-info-tip">📌 请输入邮箱或手机号，用于找回密码</div>
            </el-form-item>
            <el-form-item label="密码">
              <el-input ref="loginPwdRef" v-model="loginForm.password" type="password" show-password size="large" placeholder="请输入密码">
                <template #prefix><span class="prefix-emoji" aria-hidden="true" tabindex="-1">🔒</span></template>
              </el-input>
              <div class="field-info-tip">🔒 密码必须 8-64 位，不要和其他网站相同</div>
              <div v-if="loginForm.password && loginForm.password.length < 8" class="field-error-tip">❌ 密码太短：至少 8 个字符（现在{{ loginForm.password.length }}位）</div>
            </el-form-item>
            <el-button type="primary" size="large" class="submit-btn" aria-label="提交登录" :loading="loading" @click="onLogin">登录</el-button>
          </el-form>
          <RouterLink to="/forgot-password" class="forgot-password">忘记密码？</RouterLink>
        </el-tab-pane>
        <el-tab-pane label="注册" name="register">
          <el-form :model="regForm" label-position="top" @submit.prevent="onRegister">
            <el-form-item label="昵称">
              <el-input ref="regNickRef" v-model="regForm.nickname" size="large" clearable placeholder="怎么称呼您？（必填）">
                <template #prefix><span class="prefix-emoji" aria-hidden="true" tabindex="-1">😊</span></template>
              </el-input>
              <div class="field-info-tip">😊 怎么称呼您，家里人就会这样显示</div>
              <div v-if="regForm.nickname && regForm.nickname.trim().length < 2" class="field-error-tip">❌ 昵称至少 2 个汉字</div>
              <div v-if="!regForm.nickname.trim()" class="field-error-tip">⚠️ 请填写昵称（必填）</div>
            </el-form-item>
            <el-form-item label="邮箱">
              <el-input ref="regEmailRef" v-model="regForm.email" size="large" clearable placeholder="邮箱（选填）">
                <template #prefix><span class="prefix-emoji" aria-hidden="true" tabindex="-1">✉️</span></template>
              </el-input>
              <div class="field-info-tip">✉️ 邮箱或手机号至少填一个就行</div>
            </el-form-item>
            <el-form-item label="手机号">
              <el-input ref="regPhoneRef" v-model="regForm.phone" size="large" clearable placeholder="手机号（选填）">
                <template #prefix><span class="prefix-emoji" aria-hidden="true" tabindex="-1">📱</span></template>
              </el-input>
              <div class="field-info-tip">📱 邮箱和手机号填写其中任意一个都行</div>
              <div v-if="regForm.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(regForm.email)" class="field-error-tip">❌ 邮箱格式不对，请检查是否有 @ 和 .com/.cn 这类后缀</div>
              <div v-if="regForm.phone && !/^1\d{10}$/.test(regForm.phone)" class="field-error-tip">❌ 手机号格式不对，请输入 11 位以 1 开头的手机号</div>
              <div v-if="!regForm.email.trim() && !regForm.phone.trim()" class="field-error-tip">⚠️ 邮箱和手机号至少填一个</div>
            </el-form-item>
            <el-form-item label="密码">
              <el-input ref="regPwdRef" v-model="regForm.password" type="password" show-password size="large" placeholder="设置密码（8-64位）">
                <template #prefix><span class="prefix-emoji" aria-hidden="true" tabindex="-1">🔒</span></template>
              </el-input>
              <div class="field-info-tip">🔒 密码必须 8-64 位，至少 8 个字符</div>
              <div v-if="regForm.password && regForm.password.length < 8" class="field-error-tip">❌ 密码太短：至少 8 个字符（现在{{ regForm.password.length }}位）</div>
              <div v-if="regForm.password && regForm.password.length > 64" class="field-error-tip">❌ 密码太长：最多 64 位</div>
            </el-form-item>
            <el-form-item label="加入方式">
              <el-radio-group v-model="regForm.familyMode" size="large" aria-label="注册后创建自己的家庭，或使用邀请码加入已有家庭">
                <el-radio-button value="create">创建我的家庭</el-radio-button>
                <el-radio-button value="join">输入邀请码加入</el-radio-button>
                <el-radio-button value="later">稍后再选</el-radio-button>
              </el-radio-group>
              <div class="field-info-tip">可先加入家人已有的家庭；稍后也能在“家庭管理”中创建或加入。</div>
            </el-form-item>
            <el-form-item v-if="regForm.familyMode === 'create'" label="家庭名">
              <el-input ref="regFamRef" v-model="regForm.familyName" size="large" clearable placeholder="家庭名称（选填，默认 昵称的家）">
                <template #prefix><span class="prefix-emoji" aria-hidden="true" tabindex="-1">🏠</span></template>
              </el-input>
              <div class="field-info-tip">🏠 留空则默认用"昵称的家"，例如叫"小华家"</div>
            </el-form-item>
            <el-form-item v-if="regForm.familyMode === 'join'" label="邀请码">
              <el-input v-model="regForm.inviteCode" size="large" maxlength="6" placeholder="输入 6 位邀请码" aria-label="输入 6 位邀请码加入家庭" @input="onInviteCodeInput">
                <template #prefix><span class="prefix-emoji" aria-hidden="true" tabindex="-1">📨</span></template>
              </el-input>
              <div class="field-info-tip">邀请码由家庭管理员创建并分享给您。</div>
            </el-form-item>
            <el-button type="primary" size="large" class="submit-btn" :aria-label="registerButtonText" :loading="loading" @click="onRegister">{{ registerButtonText }}</el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref, computed, nextTick } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { safeNext } from '../utils/safeNext'
import { ElMessage } from 'element-plus'
import { authApi } from '../api/auth'
import { useAuthStore } from '../stores/auth'
import { useFamilyStore } from '../stores/family'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const familyStore = useFamilyStore()
const activeTab = ref('login')
const loading = ref(false)

const loginIdRef = ref(null)
const loginPwdRef = ref(null)
const regNickRef = ref(null)
const regEmailRef = ref(null)
const regPhoneRef = ref(null)
const regPwdRef = ref(null)
const regFamRef = ref(null)

const loginForm = reactive({ identity: '', password: '' })
const regForm = reactive({ nickname: '', email: '', phone: '', password: '', familyName: '', familyMode: 'create', inviteCode: '' })
const registerButtonText = computed(() => {
  if (regForm.familyMode === 'join') return '注册并加入家庭'
  if (regForm.familyMode === 'later') return '注册，稍后选择家庭'
  return '注册并创建家庭'
})

function onInviteCodeInput(value) {
  regForm.inviteCode = (value || '').toUpperCase().replace(/[^A-Z0-9]/g, '')
}

async function focusField(key) {
  const map = {
    'login.identity': loginIdRef,
    'login.password': loginPwdRef,
    'reg.nickname':   regNickRef,
    'reg.email':      regEmailRef,
    'reg.phone':      regPhoneRef,
    'reg.password':   regPwdRef,
    'reg.familyName': regFamRef
  }
  const target = map[key]?.value
  if (target) {
    await nextTick()
    if (typeof target.focus === 'function') {
      target.focus()
    } else if (target.$el && typeof target.$el.focus === 'function') {
      target.$el.focus()
    }
  }
}

async function onLogin() {
  if (!loginForm.identity || !loginForm.password) {
    if (!loginForm.identity) { ElMessage.warning('请输入账号'); await focusField('login.identity'); return }
    ElMessage.warning('请输入密码'); await focusField('login.password'); return
  }
  loading.value = true
  try {
    const identity = loginForm.identity.trim()
    const payload = { password: loginForm.password }
    if (identity.includes('@')) payload.email = identity; else payload.phone = identity
    const data = await authApi.login(payload)
    const token = (data && data.accessToken) || ''
    authStore.setToken(token)
    if (data) {
      const userInfo = {
        nickname: data.nickname,
        userId: data.userId
      }
      authStore.setUserInfo(userInfo)
    }
    if (data && Array.isArray(data.families)) authStore.setFamilies(data.families)
    ElMessage.success('登录成功')
    router.push(safeNext(route.query.next))
  } catch (e) {
  } finally {
    loading.value = false
  }
}

async function onRegister() {
  if (loading.value) return
  if (!regForm.nickname.trim()) { ElMessage.warning('请填写昵称'); await focusField('reg.nickname'); return }
  if (!regForm.password || regForm.password.length < 8 || regForm.password.length > 64) {
    ElMessage.warning('密码需要 8-64 位'); await focusField('reg.password'); return
  }
  if (!regForm.email && !regForm.phone) { ElMessage.warning('邮箱和手机号至少填一个'); await focusField('reg.email'); return }
  if (regForm.familyMode === 'join' && regForm.inviteCode.trim().length !== 6) {
    ElMessage.warning('请输入 6 位邀请码')
    return
  }
  loading.value = true
  try {
    const payload = {
      nickname: regForm.nickname.trim(),
      password: regForm.password,
      email: regForm.email ? regForm.email.trim() : '',
      phone: regForm.phone ? regForm.phone.trim() : '',
      familyName: regForm.familyMode === 'create' && regForm.familyName ? regForm.familyName.trim() : '',
      createFamily: regForm.familyMode === 'create',
      inviteCode: regForm.familyMode === 'join' ? regForm.inviteCode.trim() : null
    }
    const data = await authApi.register(payload)
    const token = (data && data.accessToken) || ''
    if (token) {
      authStore.setToken(token)
      if (data) {
        const userInfo = {
          userId: data.userId,
          email: data.email,
          nickname: data.nickname,
          familyId: data.familyId
        }
        authStore.setUserInfo(userInfo)
      }
      // 注册成功后由布局读取家庭；读取失败可重试，不再次提交注册。
      authStore.setFamilies([])
      familyStore.clear()
      ElMessage.success(data?.familyId ? '注册成功，欢迎加入家庭助手' : '注册成功，请先选择一个家庭')
      router.push(data?.familyId ? '/' : '/families')
    } else {
      ElMessage.success('注册成功，请登录')
      activeTab.value = 'login'
      loginForm.identity = regForm.email || regForm.phone
      loginForm.password = regForm.password
    }
  } catch (e) {
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(180deg, #f5f7fa 0%, #e8edf3 100%);
  padding: 16px;
}
.login-card {
  width: 100%;
  max-width: 480px;
  padding: 16px 20px 24px;
  border-radius: 16px;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.08);
}
.login-title {
  text-align: center;
  margin: 8px 0 20px;
  font-size: 28px;
  color: #303133;
  font-weight: 700;
}
.prefix-emoji {
  font-size: 20px;
  line-height: 1;
}
.submit-btn {
  width: 100%;
  margin-top: 8px;
  font-size: 18px;
  letter-spacing: 2px;
}
:deep(.el-form-item__label) {
  font-size: 16px;
  color: #303133;
  font-weight: 500;
}
:deep(.el-input__inner) {
  font-size: 16px;
}
:deep(.el-tabs__item) {
  font-size: 18px;
  height: 52px;
  line-height: 52px;
}
</style>
<style scoped>
.forgot-password { display: inline-block; padding: 12px 0; font-size: 18px; color: #1d4ed8; }
</style>
