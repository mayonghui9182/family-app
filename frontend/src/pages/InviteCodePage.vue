<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { 
  ArrowLeft, 
  QrCode, 
  User, 
  Clock, 
  Ban,
  Plus,
  Copy,
  CheckCircle,
  XCircle,
  AlertCircle
} from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ElMessage, ElMessageBox } from 'element-plus'
import { familyApi } from '@/api'
import { mockInviteList } from '@/mock'
import type { InviteCode } from '@/types'
import { generateId } from '@/utils'
import { dayjs } from '@/utils'

const router = useRouter()
const authStore = useAuthStore()

const inviteList = ref<InviteCode[]>([])
const loading = ref(false)
const showGenerateDialog = ref(false)

const generateForm = ref({
  maxCount: 5,
  expireHours: 24 * 7,
})

const isAdmin = computed(() => {
  return authStore.role === 'admin'
})

onMounted(async () => {
  await fetchInviteList()
})

async function fetchInviteList() {
  loading.value = true
  try {
    const data = await familyApi.getInviteList()
    inviteList.value = data
  } catch (e) {
    console.warn('使用mock邀请码数据:', e)
    inviteList.value = mockInviteList
  } finally {
    loading.value = false
  }
}

function handleBack() {
  router.back()
}

function getStatusText(status: string) {
  switch (status) {
    case 'active':
      return '有效'
    case 'expired':
      return '已过期'
    case 'disabled':
      return '已禁用'
    default:
      return status
  }
}

function getStatusColor(status: string) {
  switch (status) {
    case 'active':
      return 'text-green-500'
    case 'expired':
      return 'text-gray-400'
    case 'disabled':
      return 'text-red-500'
    default:
      return 'text-gray-500'
  }
}

function getStatusBgColor(status: string) {
  switch (status) {
    case 'active':
      return 'bg-green-50'
    case 'expired':
      return 'bg-gray-50'
    case 'disabled':
      return 'bg-red-50'
    default:
      return 'bg-gray-50'
  }
}

function getStatusIcon(status: string) {
  switch (status) {
    case 'active':
      return CheckCircle
    case 'expired':
      return AlertCircle
    case 'disabled':
      return XCircle
    default:
      return AlertCircle
  }
}

async function handleCopyCode(code: string) {
  try {
    await navigator.clipboard.writeText(code)
    ElMessage.success('邀请码已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

async function handleDisableInvite(invite: InviteCode) {
  try {
    await ElMessageBox.confirm(`确定要禁用邀请码"${invite.code}"吗？`, '提示', {
      confirmButtonText: '确定禁用',
      cancelButtonText: '取消',
      type: 'warning',
      confirmButtonClass: 'el-button--danger',
    })
    
    try {
      await familyApi.disableInvite(invite.id)
      const target = inviteList.value.find(i => i.id === invite.id)
      if (target) {
        target.status = 'disabled'
      }
      ElMessage.success('已禁用')
    } catch (e) {
      console.warn('使用mock禁用邀请码:', e)
      const target = inviteList.value.find(i => i.id === invite.id)
      if (target) {
        target.status = 'disabled'
      }
      ElMessage.success('已禁用')
    }
  } catch {
  }
}

function openGenerateDialog() {
  generateForm.value = {
    maxCount: 5,
    expireHours: 24 * 7,
  }
  showGenerateDialog.value = true
}

async function handleGenerate() {
  try {
    const data = await familyApi.generateInviteCode(
      generateForm.value.maxCount,
      generateForm.value.expireHours
    )
    inviteList.value.unshift(data)
    ElMessage.success('邀请码生成成功')
    showGenerateDialog.value = false
  } catch (e) {
    console.warn('使用mock生成邀请码:', e)
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
    let code = ''
    for (let i = 0; i < 6; i++) {
      code += chars.charAt(Math.floor(Math.random() * chars.length))
    }
    
    const newInvite: InviteCode = {
      id: generateId(),
      code,
      creatorName: authStore.userName || '我',
      usedCount: 0,
      maxCount: generateForm.value.maxCount,
      status: 'active',
      expireAt: dayjs().add(generateForm.value.expireHours, 'hour').format('YYYY-MM-DD HH:mm'),
      createdAt: dayjs().format('YYYY-MM-DD HH:mm'),
    }
    
    inviteList.value.unshift(newInvite)
    ElMessage.success('邀请码生成成功')
    showGenerateDialog.value = false
  }
}

const expireOptions = [
  { label: '1小时', value: 1 },
  { label: '6小时', value: 6 },
  { label: '1天', value: 24 },
  { label: '3天', value: 24 * 3 },
  { label: '7天', value: 24 * 7 },
  { label: '30天', value: 24 * 30 },
]

const countOptions = [
  { label: '1次', value: 1 },
  { label: '3次', value: 3 },
  { label: '5次', value: 5 },
  { label: '10次', value: 10 },
  { label: '不限次', value: 999 },
]
</script>

<template>
  <div class="min-h-screen bg-app flex flex-col">
    <div class="bg-white px-4 py-4 flex items-center border-b border-gray-100 sticky top-0 z-10">
      <button
        @click="handleBack"
        class="w-12 h-12 flex items-center justify-center rounded-full active:bg-gray-100 transition-colors -ml-2"
      >
        <ArrowLeft :size="24" class="text-gray-700" />
      </button>
      <h1 class="flex-1 text-xl font-bold text-center text-gray-800 pr-10">邀请码管理</h1>
    </div>

    <div class="flex-1 overflow-auto px-4 py-4">
      <div v-if="loading" class="flex justify-center py-12">
        <div class="text-gray-400 text-lg">加载中...</div>
      </div>

      <div v-else-if="inviteList.length === 0" class="flex flex-col items-center justify-center py-16">
        <QrCode :size="64" class="text-gray-300 mb-4" />
        <p class="text-gray-400 text-lg">暂无邀请码</p>
      </div>

      <div v-else class="space-y-3">
        <div
          v-for="invite in inviteList"
          :key="invite.id"
          class="bg-white rounded-2xl p-5 shadow-sm"
        >
          <div class="flex items-center justify-between mb-4">
            <div class="flex items-center gap-3">
              <div class="w-12 h-12 rounded-xl bg-primary-50 flex items-center justify-center">
                <QrCode :size="24" class="text-primary-500" />
              </div>
              <div>
                <div class="text-sm text-gray-500">邀请码</div>
                <div class="text-2xl font-bold text-gray-800 font-mono tracking-wider">
                  {{ invite.code }}
                </div>
              </div>
            </div>
            <span 
              :class="[getStatusBgColor(invite.status), getStatusColor(invite.status)]"
              class="px-3 py-1.5 rounded-full text-sm font-medium inline-flex items-center gap-1"
            >
              <component :is="getStatusIcon(invite.status)" :size="14" />
              {{ getStatusText(invite.status) }}
            </span>
          </div>

          <div class="grid grid-cols-2 gap-3 mb-4">
            <div class="bg-gray-50 rounded-xl p-3">
              <div class="flex items-center gap-1 text-gray-500 text-sm mb-1">
                <User :size="14" />
                <span>创建人</span>
              </div>
              <div class="text-gray-800 font-medium text-base">{{ invite.creatorName }}</div>
            </div>
            <div class="bg-gray-50 rounded-xl p-3">
              <div class="flex items-center gap-1 text-gray-500 text-sm mb-1">
                <QrCode :size="14" />
                <span>使用次数</span>
              </div>
              <div class="text-gray-800 font-medium text-base">
                <span :class="invite.usedCount >= invite.maxCount ? 'text-red-500' : 'text-primary-500'">{{ invite.usedCount }}</span>
                <span class="text-gray-400"> / {{ invite.maxCount === 999 ? '不限' : invite.maxCount }}</span>
              </div>
            </div>
          </div>

          <div class="flex items-center gap-1 text-gray-500 text-sm mb-4">
            <Clock :size="14" />
            <span>过期时间：{{ invite.expireAt }}</span>
          </div>

          <div class="flex gap-3">
            <button
              @click="handleCopyCode(invite.code)"
              class="flex-1 py-3 rounded-xl font-medium text-base bg-primary-50 text-primary-600 active:bg-primary-100 transition-colors flex items-center justify-center gap-2"
            >
              <Copy :size="18" />
              复制邀请码
            </button>
            <button
              v-if="invite.status === 'active' && isAdmin"
              @click="handleDisableInvite(invite)"
              class="flex-1 py-3 rounded-xl font-medium text-base bg-red-50 text-red-500 active:bg-red-100 transition-colors flex items-center justify-center gap-2"
            >
              <Ban :size="18" />
              禁用
            </button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="isAdmin" class="px-4 py-4 bg-white border-t border-gray-100 safe-area-bottom">
      <button
        @click="openGenerateDialog"
        class="w-full py-4 bg-gradient-to-r from-primary-500 to-primary-600 text-white rounded-2xl font-bold text-lg shadow-lg active:opacity-90 transition-opacity flex items-center justify-center gap-2"
      >
        <Plus :size="22" />
        生成新邀请码
      </button>
    </div>

    <el-dialog
      v-model="showGenerateDialog"
      title="生成邀请码"
      width="90%"
      :close-on-click-modal="false"
      class="invite-dialog"
    >
      <div class="py-4 space-y-5">
        <div>
          <label class="block text-base font-medium text-gray-700 mb-2">最大使用次数</label>
          <el-select 
            v-model="generateForm.maxCount" 
            class="w-full"
            size="large"
          >
            <el-option
              v-for="option in countOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </div>

        <div>
          <label class="block text-base font-medium text-gray-700 mb-2">有效期限</label>
          <el-select 
            v-model="generateForm.expireHours" 
            class="w-full"
            size="large"
          >
            <el-option
              v-for="option in expireOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </div>
      </div>

      <template #footer>
        <div class="flex gap-3">
          <button
            @click="showGenerateDialog = false"
            class="flex-1 py-3 rounded-xl font-medium text-base bg-gray-50 text-gray-600 active:bg-gray-100 transition-colors"
          >
            取消
          </button>
          <button
            @click="handleGenerate"
            class="flex-1 py-3 rounded-xl font-medium text-base bg-gradient-to-r from-primary-500 to-primary-600 text-white active:opacity-90 transition-opacity"
          >
            生成
          </button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.invite-dialog :deep(.el-dialog) {
  border-radius: 1.5rem;
}

.invite-dialog :deep(.el-dialog__header) {
  padding: 1.5rem 1.5rem 1rem;
}

.invite-dialog :deep(.el-dialog__title) {
  font-size: 1.25rem;
  font-weight: 700;
}

.invite-dialog :deep(.el-dialog__body) {
  padding: 0 1.5rem;
}

.invite-dialog :deep(.el-dialog__footer) {
  padding: 1rem 1.5rem 1.5rem;
}
</style>
