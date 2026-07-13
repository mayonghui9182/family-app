<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { 
  ArrowLeft, 
  Bell, 
  Volume2, 
  Vibrate, 
  Smartphone,
  CheckCircle,
  XCircle,
  AlertCircle
} from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { usePush } from '@/composables/usePush'
import { pushApi } from '@/api'

const router = useRouter()
const { 
  isSupported, 
  notificationPermission, 
  requestNotificationPermission,
  showNotification 
} = usePush()

const reminderEnabled = ref(true)
const soundEnabled = ref(true)
const vibrationEnabled = ref(true)
const devices = ref<any[]>([])
const loading = ref(false)

onMounted(async () => {
  const savedSettings = localStorage.getItem('notificationSettings')
  if (savedSettings) {
    try {
      const settings = JSON.parse(savedSettings)
      reminderEnabled.value = settings.reminderEnabled ?? true
      soundEnabled.value = settings.soundEnabled ?? true
      vibrationEnabled.value = settings.vibrationEnabled ?? true
    } catch (e) {
      console.error('解析通知设置失败:', e)
    }
  }

  await fetchDevices()
})

const saveSettings = () => {
  localStorage.setItem('notificationSettings', JSON.stringify({
    reminderEnabled: reminderEnabled.value,
    soundEnabled: soundEnabled.value,
    vibrationEnabled: vibrationEnabled.value,
  }))
}

const fetchDevices = async () => {
  loading.value = true
  try {
    const data = await pushApi.getDevices()
    devices.value = data
  } catch (e) {
    console.warn('使用mock设备数据:', e)
    devices.value = [
      {
        id: '1',
        deviceName: '我的手机',
        deviceType: 'mobile',
        registeredAt: '2024-01-15 10:30',
        isActive: true,
      },
      {
        id: '2',
        deviceName: '平板',
        deviceType: 'tablet',
        registeredAt: '2024-02-20 14:20',
        isActive: false,
      },
    ]
  } finally {
    loading.value = false
  }
}

const handleBack = () => {
  router.back()
}

const handleNotificationToggle = async (value: boolean) => {
  if (value) {
    const granted = await requestNotificationPermission()
    if (!granted) {
      ElMessage.warning('通知权限未开启，将无法接收通知')
    } else {
      ElMessage.success('通知权限已开启')
    }
  }
  saveSettings()
}

const handleReminderToggle = () => {
  saveSettings()
  if (reminderEnabled.value) {
    ElMessage.success('提醒通知已开启')
  }
}

const handleSoundToggle = () => {
  saveSettings()
}

const handleVibrationToggle = () => {
  saveSettings()
}

const testNotification = () => {
  if (notificationPermission.value === 'granted') {
    showNotification('测试通知', {
      body: '这是一条测试通知，确认您能正常接收通知',
      icon: '/favicon.svg',
    })
  } else {
    ElMessage.warning('请先开启通知权限')
  }
}

const getPermissionText = () => {
  switch (notificationPermission.value) {
    case 'granted':
      return '已开启'
    case 'denied':
      return '已拒绝'
    default:
      return '未设置'
  }
}

const getPermissionColor = () => {
  switch (notificationPermission.value) {
    case 'granted':
      return 'text-green-500'
    case 'denied':
      return 'text-red-500'
    default:
      return 'text-gray-500'
  }
}

const getPermissionBgColor = () => {
  switch (notificationPermission.value) {
    case 'granted':
      return 'bg-green-50'
    case 'denied':
      return 'bg-red-50'
    default:
      return 'bg-gray-50'
  }
}

const getPermissionIcon = () => {
  switch (notificationPermission.value) {
    case 'granted':
      return CheckCircle
    case 'denied':
      return XCircle
    default:
      return AlertCircle
  }
}

const getDeviceTypeText = (type: string) => {
  switch (type) {
    case 'mobile':
      return '手机'
    case 'tablet':
      return '平板'
    case 'desktop':
      return '电脑'
    default:
      return type
  }
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
      <h1 class="flex-1 text-xl font-bold text-center text-gray-800 pr-10">通知设置</h1>
    </div>

    <div class="flex-1 overflow-auto px-4 py-4 space-y-4 pb-8">
      <div class="bg-white rounded-2xl p-5 shadow-sm">
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-4">
            <div class="w-12 h-12 rounded-xl bg-orange-50 flex items-center justify-center">
              <Bell :size="24" class="text-orange-500" />
            </div>
            <div>
              <div class="text-lg font-semibold text-gray-800">通知权限</div>
              <div class="text-base text-gray-500">允许应用发送通知</div>
            </div>
          </div>
          <span 
            :class="[getPermissionBgColor(), getPermissionColor()]"
            class="px-3 py-1.5 rounded-full text-base font-medium inline-flex items-center gap-1"
          >
            <component :is="getPermissionIcon()" :size="16" />
            {{ getPermissionText() }}
          </span>
        </div>

        <div class="mt-5 flex items-center justify-between pt-4 border-t border-gray-50">
          <div class="text-base text-gray-700">接收通知</div>
          <el-switch 
            :model-value="notificationPermission === 'granted'"
            @change="handleNotificationToggle"
            size="large"
            active-color="#FF8C42"
            :disabled="!isSupported"
          />
        </div>

        <button
          v-if="notificationPermission === 'granted'"
          @click="testNotification"
          class="w-full mt-4 py-3 rounded-xl font-medium text-base bg-orange-50 text-orange-600 active:bg-orange-100 transition-colors"
        >
          发送测试通知
        </button>
      </div>

      <div class="bg-white rounded-2xl p-5 shadow-sm">
        <h3 class="text-lg font-semibold text-gray-800 mb-4">提醒设置</h3>
        
        <div class="space-y-4">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-4">
              <div class="w-10 h-10 rounded-xl bg-blue-50 flex items-center justify-center">
                <Bell :size="20" class="text-blue-500" />
              </div>
              <div>
                <div class="text-base font-medium text-gray-800">提醒通知</div>
                <div class="text-sm text-gray-500">待办提醒、疫苗提醒等</div>
              </div>
            </div>
            <el-switch 
              v-model="reminderEnabled"
              @change="handleReminderToggle"
              size="large"
              active-color="#FF8C42"
            />
          </div>

          <div class="flex items-center justify-between">
            <div class="flex items-center gap-4">
              <div class="w-10 h-10 rounded-xl bg-green-50 flex items-center justify-center">
                <Volume2 :size="20" class="text-green-500" />
              </div>
              <div>
                <div class="text-base font-medium text-gray-800">声音</div>
                <div class="text-sm text-gray-500">通知时播放提示音</div>
              </div>
            </div>
            <el-switch 
              v-model="soundEnabled"
              @change="handleSoundToggle"
              size="large"
              active-color="#FF8C42"
            />
          </div>

          <div class="flex items-center justify-between">
            <div class="flex items-center gap-4">
              <div class="w-10 h-10 rounded-xl bg-purple-50 flex items-center justify-center">
                <Vibrate :size="20" class="text-purple-500" />
              </div>
              <div>
                <div class="text-base font-medium text-gray-800">振动</div>
                <div class="text-sm text-gray-500">通知时振动提醒</div>
              </div>
            </div>
            <el-switch 
              v-model="vibrationEnabled"
              @change="handleVibrationToggle"
              size="large"
              active-color="#FF8C42"
            />
          </div>
        </div>
      </div>

      <div class="bg-white rounded-2xl p-5 shadow-sm">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-semibold text-gray-800">已注册设备</h3>
          <span class="text-sm text-gray-500">{{ devices.length }} 台</span>
        </div>

        <div v-if="loading" class="flex justify-center py-8">
          <div class="text-gray-400 text-base">加载中...</div>
        </div>

        <div v-else-if="devices.length === 0" class="flex flex-col items-center justify-center py-8">
          <Smartphone :size="48" class="text-gray-300 mb-3" />
          <p class="text-gray-400 text-base">暂无注册设备</p>
        </div>

        <div v-else class="space-y-3">
          <div
            v-for="device in devices"
            :key="device.id"
            class="flex items-center justify-between p-4 bg-gray-50 rounded-xl"
          >
            <div class="flex items-center gap-3">
              <div class="w-10 h-10 rounded-xl bg-white flex items-center justify-center">
                <Smartphone :size="20" class="text-gray-500" />
              </div>
              <div>
                <div class="text-base font-medium text-gray-800">{{ device.deviceName }}</div>
                <div class="text-sm text-gray-500">{{ getDeviceTypeText(device.deviceType) }} · {{ device.registeredAt }}</div>
              </div>
            </div>
            <span 
              :class="device.isActive ? 'bg-green-50 text-green-500' : 'bg-gray-100 text-gray-400'"
              class="px-3 py-1 rounded-full text-sm font-medium"
            >
              {{ device.isActive ? '在线' : '离线' }}
            </span>
          </div>
        </div>
      </div>

      <div v-if="!isSupported" class="bg-yellow-50 rounded-2xl p-5 border border-yellow-100">
        <div class="flex items-start gap-3">
          <AlertCircle :size="20" class="text-yellow-500 flex-shrink-0 mt-0.5" />
          <div>
            <div class="text-base font-medium text-yellow-700">浏览器不支持通知</div>
            <div class="text-sm text-yellow-600 mt-1">当前浏览器不支持通知功能，如需使用请更换浏览器或在系统设置中开启通知权限。</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
