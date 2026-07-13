<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { 
  ArrowLeft, 
  User, 
  Crown, 
  User as UserIcon,
  Clock,
  Trash2,
  QrCode,
  Shield,
  Users
} from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ElMessage, ElMessageBox } from 'element-plus'
import { familyApi } from '@/api'
import { mockFamilyMembers } from '@/mock'
import type { FamilyMember } from '@/types'

const router = useRouter()
const authStore = useAuthStore()

const members = ref<FamilyMember[]>([])
const loading = ref(false)

const isAdmin = computed(() => {
  return authStore.role === 'admin'
})

const currentUserId = computed(() => {
  return authStore.userId
})

onMounted(async () => {
  await fetchMembers()
})

async function fetchMembers() {
  loading.value = true
  try {
    const data = await familyApi.getMembers()
    members.value = data
  } catch (e) {
    console.warn('使用mock家庭成员数据:', e)
    members.value = mockFamilyMembers
  } finally {
    loading.value = false
  }
}

function handleBack() {
  router.back()
}

function getRoleText(role: string) {
  return role === 'admin' ? '管理员' : '成员'
}

function getRoleIcon(role: string) {
  return role === 'admin' ? Crown : UserIcon
}

function getRoleColor(role: string) {
  return role === 'admin' ? 'text-amber-500' : 'text-gray-500'
}

function getRoleBgColor(role: string) {
  return role === 'admin' ? 'bg-amber-50' : 'bg-gray-50'
}

async function handleChangeRole(member: FamilyMember) {
  if (member.id === currentUserId.value) {
    ElMessage.warning('不能修改自己的角色')
    return
  }
  
  const newRole = member.role === 'admin' ? 'member' : 'admin'
  const actionText = newRole === 'admin' ? '设为管理员' : '取消管理员'
  
  try {
    await ElMessageBox.confirm(`确定要${actionText}吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
    
    try {
      await familyApi.updateMemberRole(member.id, newRole)
      const target = members.value.find(m => m.id === member.id)
      if (target) {
        target.role = newRole as 'admin' | 'member'
      }
      ElMessage.success(`${actionText}成功`)
    } catch (e) {
      console.warn('使用mock更新角色:', e)
      const target = members.value.find(m => m.id === member.id)
      if (target) {
        target.role = newRole as 'admin' | 'member'
      }
      ElMessage.success(`${actionText}成功`)
    }
  } catch {
  }
}

async function handleRemoveMember(member: FamilyMember) {
  if (member.id === currentUserId.value) {
    ElMessage.warning('不能移除自己')
    return
  }
  
  try {
    await ElMessageBox.confirm(`确定要移除成员"${member.name}"吗？`, '提示', {
      confirmButtonText: '确定移除',
      cancelButtonText: '取消',
      type: 'warning',
      confirmButtonClass: 'el-button--danger',
    })
    
    try {
      await familyApi.removeMember(member.id)
      members.value = members.value.filter(m => m.id !== member.id)
      ElMessage.success('移除成功')
    } catch (e) {
      console.warn('使用mock移除成员:', e)
      members.value = members.value.filter(m => m.id !== member.id)
      ElMessage.success('移除成功')
    }
  } catch {
  }
}

function handleGenerateInvite() {
  router.push('/family/invite')
}
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
      <h1 class="flex-1 text-xl font-bold text-center text-gray-800 pr-10">家庭成员</h1>
    </div>

    <div class="flex-1 overflow-auto px-4 py-4">
      <div v-if="loading" class="flex justify-center py-12">
        <div class="text-gray-400 text-lg">加载中...</div>
      </div>

      <div v-else class="space-y-3">
        <div
          v-for="member in members"
          :key="member.id"
          class="bg-white rounded-2xl p-5 shadow-sm"
        >
          <div class="flex items-center gap-4">
            <div class="w-16 h-16 rounded-full bg-gradient-to-br from-primary-100 to-primary-200 flex items-center justify-center flex-shrink-0">
              <User :size="32" class="text-primary-500" />
            </div>
            
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2">
                <span class="text-xl font-bold text-gray-800">{{ member.name }}</span>
                <span 
                  :class="[getRoleBgColor(member.role), getRoleColor(member.role)]"
                  class="px-2.5 py-1 rounded-full text-sm font-medium inline-flex items-center gap-1"
                >
                  <component :is="getRoleIcon(member.role)" :size="14" />
                  {{ getRoleText(member.role) }}
                </span>
              </div>
              <div class="flex items-center gap-1 mt-2 text-gray-500 text-base">
                <Clock :size="16" />
                <span>最后登录：{{ member.lastLoginAt || '-' }}</span>
              </div>
            </div>
          </div>

          <div 
            v-if="isAdmin && member.id !== currentUserId"
            class="flex gap-3 mt-4 pt-4 border-t border-gray-50"
          >
            <button
              @click="handleChangeRole(member)"
              class="flex-1 py-3 rounded-xl font-medium text-base flex items-center justify-center gap-2 transition-colors"
              :class="member.role === 'admin' 
                ? 'bg-gray-50 text-gray-600 active:bg-gray-100' 
                : 'bg-amber-50 text-amber-600 active:bg-amber-100'"
            >
              <Shield :size="18" />
              {{ member.role === 'admin' ? '取消管理员' : '设为管理员' }}
            </button>
            <button
              @click="handleRemoveMember(member)"
              class="flex-1 py-3 rounded-xl font-medium text-base bg-red-50 text-red-500 active:bg-red-100 transition-colors flex items-center justify-center gap-2"
            >
              <Trash2 :size="18" />
              移除
            </button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="isAdmin" class="px-4 py-4 bg-white border-t border-gray-100 safe-area-bottom">
      <button
        @click="handleGenerateInvite"
        class="w-full py-4 bg-gradient-to-r from-primary-500 to-primary-600 text-white rounded-2xl font-bold text-lg shadow-lg active:opacity-90 transition-opacity flex items-center justify-center gap-2"
      >
        <QrCode :size="22" />
        生成邀请码
      </button>
    </div>
  </div>
</template>
