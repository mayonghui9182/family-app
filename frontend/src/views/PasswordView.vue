<template>
  <div class="password-page">
    <el-card class="password-card" role="region" aria-label="修改密码页面">
      <h2 class="page-title">🔐 修改密码</h2>
      <p class="page-subtitle">为账号安全，请先输入当前密码再设置新密码</p>

      <el-form
        ref="passwordForm"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="submitChange"
      >
        <el-form-item label="当前密码" prop="oldPassword">
          <el-input
            ref="oldPwdRef"
            v-model="form.oldPassword"
            type="password"
            size="large"
            show-password
            clearable
            placeholder="请输入当前密码"
          >
            <template #prefix><span class="prefix-emoji" aria-hidden="true" tabindex="-1">🔒</span></template>
          </el-input>
          <div class="field-info-tip">🔒 用于身份二次确认，确保是本人操作</div>
        </el-form-item>

        <el-form-item label="新密码" prop="newPassword">
          <el-input
            ref="newPwdRef"
            v-model="form.newPassword"
            type="password"
            size="large"
            show-password
            clearable
            placeholder="请输入新密码（8-64位）"
          >
            <template #prefix><span class="prefix-emoji" aria-hidden="true" tabindex="-1">🆕</span></template>
          </el-input>
          <div class="field-info-tip">🆕 新密码需 8-64 位，建议字母数字混合</div>
          <div v-if="form.newPassword && form.newPassword.length < 8" class="field-error-tip">
            ❌ 密码太短：至少 8 个字符（现在{{ form.newPassword.length }}位）
          </div>
          <div v-if="form.newPassword && form.newPassword.length > 64" class="field-error-tip">
            ❌ 密码太长：最多 64 位
          </div>
          <div v-if="form.newPassword && form.oldPassword && form.newPassword === form.oldPassword" class="field-error-tip">
            ❌ 新密码不能和旧密码相同
          </div>
        </el-form-item>

        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input
            ref="confirmPwdRef"
            v-model="form.confirmPassword"
            type="password"
            size="large"
            show-password
            clearable
            placeholder="请再次输入新密码"
          >
            <template #prefix><span class="prefix-emoji" aria-hidden="true" tabindex="-1">✅</span></template>
          </el-input>
          <div v-if="form.confirmPassword && form.newPassword && form.confirmPassword !== form.newPassword" class="field-error-tip">
            ❌ 两次输入的新密码不一致
          </div>
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            size="large"
            class="submit-btn"
            aria-label="保存新密码"
            :loading="loading"
            @click="submitChange"
          >💾 保存新密码</el-button>
          <el-button size="large" class="cancel-btn" aria-label="取消返回更多功能" @click="goBack">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
// 修改密码页面：旧密码二次确认 + 新密码长度校验 + 新旧不同 + 两次新密码一致
// 老人友好：大输入框、emoji 前缀、show-password 防误输、中文错误提示、行内即时校验
import { reactive, ref, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '../api/auth'

const router = useRouter()
const loading = ref(false)

const passwordForm = ref(null)
const oldPwdRef = ref(null)
const newPwdRef = ref(null)
const confirmPwdRef = ref(null)

const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

// Element Plus 表单校验规则：基础长度校验，更精细的「新旧不同」「两次一致」通过行内提示即时反馈
const rules = {
  oldPassword: [
    { required: true, message: '请输入当前密码', trigger: 'blur' },
    { min: 1, max: 64, message: '密码长度 1-64 位', trigger: 'blur' }
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 64, message: '新密码长度需 8 到 64 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value && form.newPassword && value !== form.newPassword) {
          callback(new Error('两次输入的新密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

async function focusField(key) {
  const map = {
    oldPassword: oldPwdRef,
    newPassword: newPwdRef,
    confirmPassword: confirmPwdRef
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

async function submitChange() {
  // 前端兜底校验：避免老人误触发了后端往返
  if (!form.oldPassword) {
    ElMessage.warning('请输入当前密码')
    await focusField('oldPassword')
    return
  }
  if (!form.newPassword || form.newPassword.length < 8 || form.newPassword.length > 64) {
    ElMessage.warning('新密码需要 8-64 位')
    await focusField('newPassword')
    return
  }
  if (form.newPassword === form.oldPassword) {
    ElMessage.warning('新密码不能和旧密码相同')
    await focusField('newPassword')
    return
  }
  if (!form.confirmPassword) {
    ElMessage.warning('请再次输入新密码')
    await focusField('confirmPassword')
    return
  }
  if (form.confirmPassword !== form.newPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    await focusField('confirmPassword')
    return
  }

  loading.value = true
  try {
    await authApi.changePassword({
      oldPassword: form.oldPassword,
      newPassword: form.newPassword
    })
    ElMessage.success('密码修改成功')
    // 修改成功后返回更多页，避免老人误退出登录
    router.push('/more')
  } catch (e) {
    // 错误提示由 client.js 全局 toast 统一处理（中文业务码）
    // 旧密码错误等情况清空旧密码输入，引导重输
    form.oldPassword = ''
    await focusField('oldPassword')
  } finally {
    loading.value = false
  }
}

function goBack() {
  router.push('/more')
}
</script>

<style scoped>
.password-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(180deg, #f5f7fa 0%, #e8edf3 100%);
  padding: 16px;
}
.password-card {
  width: 100%;
  max-width: 480px;
  padding: 16px 20px 24px;
  border-radius: 16px;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.08);
}
.page-title {
  text-align: center;
  margin: 8px 0 6px;
  font-size: 26px;
  color: #303133;
  font-weight: 700;
}
.page-subtitle {
  text-align: center;
  font-size: 15px;
  color: #909399;
  margin: 0 0 20px;
}
.prefix-emoji {
  font-size: 20px;
  line-height: 1;
}
.submit-btn {
  width: 100%;
  margin-top: 8px;
  margin-bottom: 10px;
  font-size: 18px;
  letter-spacing: 2px;
}
.cancel-btn {
  width: 100%;
  font-size: 17px;
}
.field-info-tip {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
  line-height: 1.5;
}
.field-error-tip {
  font-size: 13px;
  color: #f56c6c;
  margin-top: 4px;
  line-height: 1.5;
}
:deep(.el-form-item__label) {
  font-size: 16px;
  color: #303133;
  font-weight: 500;
}
:deep(.el-input__inner) {
  font-size: 16px;
}
</style>
