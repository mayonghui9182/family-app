<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { 
  User, 
  Users, 
  QrCode, 
  Baby, 
  Info, 
  LogOut, 
  ChevronRight,
  Home,
  Bell
} from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ElMessage, ElMessageBox } from 'element-plus'
import { familyApi } from '@/api'
import { mockFamilyInfo } from '@/mock'
import type { FamilyInfo } from '@/types'

const router = useRouter()
const authStore = useAuthStore()

const familyInfo = ref<FamilyInfo | null>(null)
const loading = ref(false)

const menuItems = [
  {
    icon: Users,
    title: '家庭成员管理',
    path: '/family/members',
    color: 'text-primary-500',
    bgColor: 'bg-primary-50',
  },
  {
    icon: QrCode,
    title: '邀请码管理',
    path: '/family/invite',
    color: 'text-purple-500',
    bgColor: 'bg-purple-50',
  },
  {
    icon: Baby,
    title: '宝宝信息管理',
    path: '/baby',
    color: 'text-rose-500',
    bgColor: 'bg-rose-50',
  },
  {
    icon: Bell,
    title: '通知设置',
    path: '/settings/notification',
    color: 'text-orange-500',
    bgColor: 'bg-orange-50',
  },
  {
    icon: Info,
    title: '关于我们',
    path: '',
    color: 'text-gray-500',
    bgColor: 'bg-gray-50',
  },
]

onMounted(async () => {
  await fetchFamilyInfo()
})

async function fetchFamilyInfo() {
  loading.value = true
  try {
    const data = await familyApi.getFamilyInfo()
    familyInfo.value = data
  } catch (e) {
    console.warn('使用mock家庭信息数据:', e)
    familyInfo.value = mockFamilyInfo
  } finally {
    loading.value = false
  }
}

function handleMenuClick(path: string, title: string) {
  if (path) {
    router.push(path)
  } else {
    ElMessage.info(`${title}功能开发中`)
  }
}

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
    authStore.logout()
    router.push('/login')
    ElMessage.success('已退出登录')
  } catch {
  }
}
</script>

<template>
  <div class="min-h-screen bg-app">
    <div class="bg-gradient-to-br from-primary-400 to-primary-600 px-6 pt-12 pb-20">
      <div class="flex items-center gap-4">
        <div class="w-20 h-20 rounded-full bg-white/20 flex items-center justify-center">
          <User :size="40" class="text-white" />
        </div>
        <div class="flex-1">
          <h2 class="text-2xl font-bold text-white">{{ authStore.userName || '用户' }}</h2>
          <p class="text-white/80 text-lg mt-1">{{ familyInfo?.name || '幸福之家' }}</p>
        </div>
      </div>
    </div>

    <div class="px-4 -mt-10">
      <div class="bg-white rounded-2xl shadow-lg p-6">
        <div class="flex items-center justify-between mb-2">
          <div class="flex items-center gap-2">
            <Home :size="20" class="text-primary-500" />
            <span class="text-lg font-medium text-gray-800">我的家庭</span>
          </div>
          <span class="text-primary-500 font-semibold text-lg">{{ familyInfo?.memberCount || 0 }} 人</span>
        </div>
        <p class="text-gray-500 text-base">邀请码：{{ familyInfo?.inviteCode || '-' }}</p>
      </div>
    </div>

    <div class="px-4 mt-6 pb-8">
      <div class="bg-white rounded-2xl shadow-sm overflow-hidden">
        <div
          v-for="(item, index) in menuItems"
          :key="index"
          @click="handleMenuClick(item.path, item.title)"
          class="flex items-center px-5 py-5 border-b border-gray-50 last:border-b-0 active:bg-gray-50 transition-colors cursor-pointer"
        >
          <div :class="[item.bgColor, 'w-12 h-12 rounded-xl flex items-center justify-center mr-4']">
            <component :is="item.icon" :size="24" :class="item.color" />
          </div>
          <span class="flex-1 text-lg text-gray-800">{{ item.title }}</span>
          <ChevronRight :size="24" class="text-gray-400" />
        </div>
      </div>
    </div>

    <div class="px-4 pb-10">
      <button
        @click="handleLogout"
        class="w-full py-4 bg-white text-red-500 rounded-2xl font-semibold text-lg shadow-sm active:bg-red-50 transition-colors flex items-center justify-center gap-2"
      >
        <LogOut :size="22" />
        退出登录
      </button>
    </div>
  </div>
</template>
