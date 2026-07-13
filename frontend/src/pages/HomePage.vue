<script setup lang="ts">
import { onMounted, computed } from 'vue'
import { 
  Bell, 
  CheckSquare, 
  Baby, 
  ChevronRight,
  Plus,
  Calendar,
  TrendingUp,
  Award,
  Heart,
  Users,
  Package,
  AlertTriangle
} from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useTodoStore } from '@/stores/todo'
import { useReminderStore } from '@/stores/reminder'
import { useBabyStore } from '@/stores/baby'
import { useAuthStore } from '@/stores/auth'
import { useItemStore } from '@/stores/item'
import { dayjs } from '@/utils'

const router = useRouter()
const todoStore = useTodoStore()
const reminderStore = useReminderStore()
const babyStore = useBabyStore()
const authStore = useAuthStore()
const itemStore = useItemStore()

const quickActions = [
  { 
    icon: Plus, 
    title: '添加待办', 
    path: '/todos', 
    color: 'from-orange-400 to-orange-500',
    bgColor: 'bg-orange-50',
    iconColor: 'text-orange-500'
  },
  { 
    icon: Bell, 
    title: '添加提醒', 
    path: '/reminders', 
    color: 'from-pink-400 to-pink-500',
    bgColor: 'bg-pink-50',
    iconColor: 'text-pink-500'
  },
  { 
    icon: Package, 
    title: '物品库存', 
    path: '/items', 
    color: 'from-green-400 to-green-500',
    bgColor: 'bg-green-50',
    iconColor: 'text-green-500'
  },
  { 
    icon: TrendingUp, 
    title: '记录成长', 
    path: '/baby', 
    color: 'from-rose-400 to-rose-500',
    bgColor: 'bg-rose-50',
    iconColor: 'text-rose-500'
  },
  { 
    icon: Award, 
    title: '记录里程碑', 
    path: '/baby', 
    color: 'from-amber-400 to-amber-500',
    bgColor: 'bg-amber-50',
    iconColor: 'text-amber-500'
  },
]

const greeting = computed(() => {
  const hour = dayjs().hour()
  if (hour < 6) return '夜深了'
  if (hour < 9) return '早上好'
  if (hour < 12) return '上午好'
  if (hour < 14) return '中午好'
  if (hour < 17) return '下午好'
  if (hour < 19) return '傍晚好'
  return '晚上好'
})

const greetingEmoji = computed(() => {
  const hour = dayjs().hour()
  if (hour < 6) return '🌙'
  if (hour < 9) return '☀️'
  if (hour < 12) return '🌤️'
  if (hour < 14) return '🌞'
  if (hour < 17) return '🌤️'
  if (hour < 19) return '🌅'
  return '🌙'
})

const currentDate = computed(() => {
  return dayjs().format('M月D日 dddd')
})

const familyName = computed(() => {
  return authStore.familyName || '幸福的一家'
})

const memberCount = computed(() => {
  return 3
})

const babyName = computed(() => {
  return babyStore.babyInfo?.name || babyStore.babyInfo?.nickname || '宝宝'
})

const babyAgeText = computed(() => {
  if (babyStore.ageText) {
    return babyStore.ageText
  }
  if (babyStore.babyInfo?.birthDate) {
    const birth = dayjs(babyStore.babyInfo.birthDate)
    const now = dayjs()
    const months = now.diff(birth, 'month')
    const days = now.subtract(months, 'month').diff(birth, 'day')
    return `${months}个月${days}天`
  }
  return ''
})

const babyAvatar = computed(() => {
  return babyStore.babyInfo?.avatar || ''
})

const babyGender = computed(() => {
  return babyStore.babyInfo?.gender || 'girl'
})

onMounted(async () => {
  await Promise.all([
    todoStore.fetchTodayTodos(),
    reminderStore.fetchTodayReminders(),
    babyStore.fetchBabyInfo(),
    itemStore.fetchLowStockItems(),
  ])
})

const lowStockItems = computed(() => itemStore.lowStockItems)

const todayTodos = computed(() => todoStore.todayTodos)
const todayReminders = computed(() => reminderStore.todayReminders)

const handleToggleTodo = async (todo: any) => {
  try {
    await todoStore.toggleTodo(todo.id, !todo.completed)
  } catch (e) {
    console.error('切换待办状态失败:', e)
  }
}

const handleQuickAction = (action: any) => {
  router.push(action.path)
}

const getReminderIcon = (type: string) => {
  switch (type) {
    case 'birthday': return '🎂'
    case 'bill': return '💰'
    case 'health': return '💪'
    case 'social': return '🎉'
    case 'work': return '💼'
    default: return '🔔'
  }
}

const warmGreeting = computed(() => {
  const hour = dayjs().hour()
  if (hour >= 22 || hour < 6) {
    return '夜深了，早点休息哦～'
  } else if (hour >= 6 && hour < 9) {
    return '美好的一天开始啦！'
  } else if (hour >= 11 && hour < 14) {
    return '记得按时吃饭哦～'
  } else if (hour >= 17 && hour < 19) {
    return '夕阳无限好，珍惜每一天！'
  } else {
    return '愿宝宝健康快乐成长 ❤️'
  }
})
</script>

<template>
  <div class="page-container pb-20">
    <!-- 顶部渐变区域 -->
    <div class="bg-gradient-to-b from-orange-100 via-pink-50 to-transparent pb-6">
      <div class="safe-area-top"></div>
      
      <!-- 问候与日期 -->
      <div class="px-5 pt-4 mb-4">
        <div class="flex items-center justify-between">
          <div>
            <p class="text-gray-600 text-base mb-1">
              {{ greeting }} {{ greetingEmoji }}
            </p>
            <h1 class="text-2xl font-bold text-gray-800">{{ familyName }}</h1>
            <div class="flex items-center gap-1 mt-1 text-gray-500 text-sm">
              <Users :size="14" />
              <span>{{ memberCount }}位成员</span>
              <span class="mx-1">·</span>
              <span>{{ currentDate }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 宝宝信息卡片 -->
      <div class="px-5">
        <div class="bg-white rounded-3xl shadow-lg p-5 bg-gradient-to-r from-pink-50 to-orange-50">
          <div class="flex items-center gap-4">
            <div class="relative">
              <div 
                class="w-20 h-20 rounded-full flex items-center justify-center text-4xl shadow-md"
                :class="babyGender === 'girl' ? 'bg-gradient-to-br from-pink-300 to-pink-400' : 'bg-gradient-to-br from-blue-300 to-blue-400'"
              >
                <Baby :size="40" class="text-white" />
              </div>
              <div class="absolute -bottom-1 -right-1 w-7 h-7 bg-gradient-to-r from-orange-400 to-pink-400 rounded-full flex items-center justify-center text-white text-xs font-bold shadow-md">
                ❤
              </div>
            </div>
            <div class="flex-1">
              <h2 class="text-xl font-bold text-gray-800 mb-1">{{ babyName }}</h2>
              <div class="flex items-center gap-2 mb-1">
                <span class="text-2xl">👶</span>
                <span class="text-lg font-semibold text-orange-500">{{ babyAgeText }}</span>
              </div>
              <p class="text-gray-500 text-sm">
                {{ babyGender === 'girl' ? '小公主' : '小王子' }}健康成长中
              </p>
            </div>
            <div class="text-right">
              <Heart :size="24" class="text-pink-400 animate-pulse" fill="currentColor" />
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 快捷功能区 -->
    <div class="px-5 mb-6">
      <h3 class="text-lg font-bold text-gray-800 mb-4 flex items-center gap-2">
        <span class="w-1 h-5 bg-gradient-to-b from-orange-400 to-pink-400 rounded-full"></span>
        快捷功能
      </h3>
      <div class="grid grid-cols-5 gap-2">
        <button
          v-for="(action, index) in quickActions"
          :key="action.title"
          @click="handleQuickAction(action)"
          class="flex flex-col items-center justify-center p-3 rounded-2xl bg-white shadow-md hover:shadow-lg transition-all duration-300 active:scale-95"
        >
          <div 
            :class="['w-12 h-12 rounded-xl flex items-center justify-center mb-2 shadow-sm', action.bgColor]"
          >
            <component :is="action.icon" :size="24" :class="action.iconColor" />
          </div>
          <span class="text-sm font-semibold text-gray-700 text-center">{{ action.title }}</span>
        </button>
      </div>
    </div>

    <!-- 今日待办 -->
    <div class="px-5 mb-6">
      <div class="flex items-center justify-between mb-4">
        <h3 class="text-lg font-bold text-gray-800 flex items-center gap-2">
          <span class="w-1 h-5 bg-gradient-to-b from-orange-400 to-orange-500 rounded-full"></span>
          今日待办
        </h3>
        <button 
          @click="router.push('/todos')" 
          class="text-orange-500 text-base font-medium flex items-center gap-1"
        >
          查看全部 <ChevronRight :size="18" />
        </button>
      </div>
      <div class="bg-white rounded-2xl shadow-md overflow-hidden">
        <div v-if="todayTodos.length > 0" class="divide-y divide-gray-100">
          <div 
            v-for="(todo, index) in todayTodos.slice(0, 3)" 
            :key="todo.id"
            class="flex items-center gap-4 p-4 hover:bg-gray-50 transition-colors"
          >
            <div 
              :class="[
                'w-7 h-7 rounded-full border-2 flex items-center justify-center cursor-pointer transition-all flex-shrink-0',
                todo.completed 
                  ? 'bg-green-400 border-green-400' 
                  : 'border-gray-300 hover:border-orange-400'
              ]"
              @click="handleToggleTodo(todo)"
            >
              <CheckSquare v-if="todo.completed" :size="16" class="text-white" />
            </div>
            <div class="flex-1 min-w-0">
              <p :class="['font-medium text-base', todo.completed ? 'text-gray-400 line-through' : 'text-gray-700']">
                {{ todo.title }}
              </p>
              <p v-if="todo.dueTime" class="text-sm text-gray-400 mt-0.5">
                <Calendar :size="12" class="inline mr-1" />
                {{ todo.dueTime }}
              </p>
            </div>
            <span 
              :class="[
                'text-xs px-3 py-1 rounded-full font-medium',
                todo.priority === 'high' ? 'bg-red-50 text-red-500' :
                todo.priority === 'medium' ? 'bg-orange-50 text-orange-500' :
                'bg-green-50 text-green-500'
              ]"
            >
              {{ todo.priority === 'high' ? '高' : todo.priority === 'medium' ? '中' : '低' }}
            </span>
          </div>
        </div>
        <div v-else class="text-center py-10 text-gray-400">
          <CheckSquare :size="40" class="mx-auto mb-3 opacity-50" />
          <p class="text-base">今日暂无待办</p>
          <p class="text-sm mt-1">轻松愉快的一天 🌟</p>
        </div>
      </div>
    </div>

    <!-- 今日提醒 -->
    <div class="px-5 mb-6">
      <div class="flex items-center justify-between mb-4">
        <h3 class="text-lg font-bold text-gray-800 flex items-center gap-2">
          <span class="w-1 h-5 bg-gradient-to-b from-pink-400 to-pink-500 rounded-full"></span>
          今日提醒
        </h3>
        <button 
          @click="router.push('/reminders')" 
          class="text-pink-500 text-base font-medium flex items-center gap-1"
        >
          查看全部 <ChevronRight :size="18" />
        </button>
      </div>
      <div class="bg-white rounded-2xl shadow-md overflow-hidden">
        <div v-if="todayReminders.length > 0" class="divide-y divide-gray-100">
          <div 
            v-for="(reminder, index) in todayReminders.slice(0, 3)" 
            :key="reminder.id"
            class="flex items-center gap-4 p-4 hover:bg-gray-50 transition-colors"
          >
            <div class="w-12 h-12 rounded-xl bg-pink-50 flex items-center justify-center text-2xl flex-shrink-0">
              {{ getReminderIcon(reminder.type) }}
            </div>
            <div class="flex-1 min-w-0">
              <p class="font-medium text-base text-gray-700">{{ reminder.title }}</p>
              <p class="text-sm text-gray-400 mt-0.5">
                <Bell :size="12" class="inline mr-1" />
                {{ reminder.time }}
              </p>
            </div>
            <div 
              :class="[
                'w-12 h-7 rounded-full p-1 transition-all cursor-pointer',
                reminder.enabled ? 'bg-pink-400' : 'bg-gray-200'
              ]"
              @click="reminderStore.toggleReminder(reminder.id, !reminder.enabled)"
            >
              <div 
                :class="[
                  'w-5 h-5 bg-white rounded-full shadow-sm transition-all',
                  reminder.enabled ? 'translate-x-5' : 'translate-x-0'
                ]"
              ></div>
            </div>
          </div>
        </div>
        <div v-else class="text-center py-10 text-gray-400">
          <Bell :size="40" class="mx-auto mb-3 opacity-50" />
          <p class="text-base">今日暂无提醒</p>
          <p class="text-sm mt-1">安心享受美好时光 ✨</p>
        </div>
      </div>
    </div>

    <!-- 库存预警 -->
    <div v-if="lowStockItems.length > 0" class="px-5 mb-6">
      <div class="flex items-center justify-between mb-4">
        <h3 class="text-lg font-bold text-gray-800 flex items-center gap-2">
          <span class="w-1 h-5 bg-gradient-to-b from-red-400 to-red-500 rounded-full"></span>
          库存预警
        </h3>
        <button 
          @click="router.push('/items')" 
          class="text-red-500 text-base font-medium flex items-center gap-1"
        >
          查看全部 <ChevronRight :size="18" />
        </button>
      </div>
      <div class="bg-white rounded-2xl shadow-md overflow-hidden">
        <div class="divide-y divide-gray-100">
          <div 
            v-for="(item, index) in lowStockItems.slice(0, 3)" 
            :key="item.id"
            class="flex items-center gap-4 p-4 hover:bg-gray-50 transition-colors cursor-pointer"
            @click="router.push(`/items/${item.id}`)"
          >
            <div class="w-12 h-12 rounded-xl bg-red-50 flex items-center justify-center text-2xl flex-shrink-0">
              {{ item.icon }}
            </div>
            <div class="flex-1 min-w-0">
              <p class="font-medium text-base text-gray-700">{{ item.name }}</p>
              <p class="text-sm text-gray-400 mt-0.5">
                剩余 <span class="text-red-500 font-semibold">{{ item.totalQuantity }}</span> {{ item.unit }}
              </p>
            </div>
            <div class="flex items-center gap-1 text-red-500">
              <AlertTriangle :size="18" />
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部温馨提示 -->
    <div class="px-5 mb-8">
      <div class="bg-gradient-to-r from-orange-100 via-pink-100 to-rose-100 rounded-2xl p-6 text-center">
        <div class="text-3xl mb-2">💕</div>
        <p class="text-gray-700 text-base font-medium">{{ warmGreeting }}</p>
        <p class="text-gray-500 text-sm mt-2">— 爱你的家人</p>
      </div>
    </div>
  </div>
</template>
