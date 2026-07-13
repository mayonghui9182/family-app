<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { Bell, Plus, Clock, Calendar, X, Cake, Receipt, Heart, Briefcase, MoreHorizontal, Clock3 } from 'lucide-vue-next'
import { useReminderStore } from '@/stores/reminder'
import { ElMessage, ElMessageBox, ElSwitch } from 'element-plus'
import type { ReminderType, ReminderRepeat } from '@/types'
import { dayjs } from '@/utils'

const reminderStore = useReminderStore()

const activeTab = ref<'all' | 'active' | 'closed'>('all')
const showDialog = ref(false)
const isEditing = ref(false)

const formData = ref({
  title: '',
  date: dayjs().format('YYYY-MM-DD'),
  time: '09:00',
  type: 'other' as ReminderType,
  repeat: 'none' as ReminderRepeat,
  enabled: true,
  description: '',
})

const tabs = [
  { key: 'all' as const, label: '全部' },
  { key: 'active' as const, label: '进行中' },
  { key: 'closed' as const, label: '已关闭' },
]

const typeConfig: Record<ReminderType, { label: string; color: string; bg: string; borderColor: string; icon: any }> = {
  birthday: { label: '生日', color: 'text-pink-500', bg: 'bg-pink-50', borderColor: 'border-pink-200', icon: Cake },
  bill: { label: '账单', color: 'text-blue-500', bg: 'bg-blue-50', borderColor: 'border-blue-200', icon: Receipt },
  health: { label: '健康', color: 'text-green-500', bg: 'bg-green-50', borderColor: 'border-green-200', icon: Heart },
  social: { label: '社交', color: 'text-purple-500', bg: 'bg-purple-50', borderColor: 'border-purple-200', icon: MoreHorizontal },
  work: { label: '工作', color: 'text-orange-500', bg: 'bg-orange-50', borderColor: 'border-orange-200', icon: Briefcase },
  other: { label: '其他', color: 'text-gray-500', bg: 'bg-gray-50', borderColor: 'border-gray-200', icon: MoreHorizontal },
}

const repeatOptions = [
  { value: 'none', label: '不重复' },
  { value: 'daily', label: '每天' },
  { value: 'weekly', label: '每周' },
  { value: 'monthly', label: '每月' },
]

onMounted(async () => {
  await Promise.all([
    reminderStore.fetchReminders({ page: 1, pageSize: 50 }),
    reminderStore.fetchTodayReminders(),
  ])
})

const filteredReminders = computed(() => {
  if (activeTab.value === 'active') {
    return reminderStore.reminders.filter(r => r.enabled)
  }
  if (activeTab.value === 'closed') {
    return reminderStore.reminders.filter(r => !r.enabled)
  }
  return reminderStore.reminders
})

const todayCount = computed(() => {
  const today = dayjs().format('YYYY-MM-DD')
  return reminderStore.reminders.filter(r => r.enabled && r.date === today).length
})

const activeCount = computed(() => reminderStore.reminders.filter(r => r.enabled).length)
const closedCount = computed(() => reminderStore.reminders.filter(r => !r.enabled).length)

const formatDate = (date: string) => {
  const d = dayjs(date)
  if (d.isSame(dayjs(), 'day')) return '今天'
  if (d.isSame(dayjs().add(1, 'day'), 'day')) return '明天'
  if (d.isSame(dayjs().subtract(1, 'day'), 'day')) return '昨天'
  return d.format('M月D日')
}

const getRepeatLabel = (type: string) => {
  const option = repeatOptions.find(o => o.value === type)
  return option?.label || ''
}

const handleToggle = async (id: string | number, enabled: boolean) => {
  try {
    await reminderStore.toggleReminder(id, enabled)
    ElMessage.success(enabled ? '已启用' : '已关闭')
  } catch (e) {
    console.error(e)
  }
}

const handleSnooze = async (id: string | number) => {
  try {
    await reminderStore.snoozeReminder(id, 5)
    ElMessage.success('已设置5分钟后提醒')
  } catch (e) {
    console.error(e)
  }
}

const openAddDialog = () => {
  isEditing.value = false
  formData.value = {
    title: '',
    date: dayjs().format('YYYY-MM-DD'),
    time: '09:00',
    type: 'other',
    repeat: 'none',
    enabled: true,
    description: '',
  }
  showDialog.value = true
}

const handleSubmit = async () => {
  if (!formData.value.title.trim()) {
    ElMessage.warning('请输入提醒标题')
    return
  }
  try {
    if (isEditing.value) {
    } else {
      await reminderStore.createReminder(formData.value)
      ElMessage.success('添加成功')
    }
    showDialog.value = false
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

const handleDelete = async (id: string | number) => {
  try {
    await ElMessageBox.confirm('确定要删除这个提醒吗？', '删除提醒', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await reminderStore.deleteReminder(id)
    ElMessage.success('删除成功')
  } catch {
  }
}
</script>

<template>
  <div class="page-container">
    <div class="safe-area-top bg-gradient-to-b from-orange-100 to-transparent pb-4">
      <div class="px-5 pt-4">
        <div class="flex items-center justify-between mb-6">
          <h1 class="text-2xl font-bold text-gray-800">提醒中心</h1>
          <button 
            @click="openAddDialog"
            class="w-12 h-12 rounded-full bg-gradient-primary flex items-center justify-center text-white shadow-card hover:scale-105 transition-transform active:scale-95"
          >
            <Plus :size="26" />
          </button>
        </div>

        <div class="card-base bg-gradient-to-r from-orange-400 to-orange-500 text-white mb-6 animate-slide-up">
          <div class="flex items-center gap-4">
            <div class="w-16 h-16 rounded-2xl bg-white/20 backdrop-blur-sm flex items-center justify-center">
              <Bell :size="32" />
            </div>
            <div class="flex-1">
              <p class="text-white/80 text-base mb-1">今日提醒</p>
              <p class="text-4xl font-bold">{{ todayCount }}</p>
            </div>
          </div>
        </div>

        <div class="grid grid-cols-3 gap-3 mb-6">
          <div class="card-base text-center py-4">
            <p class="text-2xl font-bold text-gray-800">{{ reminderStore.reminders.length }}</p>
            <p class="text-base text-gray-500 mt-1">全部</p>
          </div>
          <div class="card-base text-center py-4">
            <p class="text-2xl font-bold text-primary-500">{{ activeCount }}</p>
            <p class="text-base text-gray-500 mt-1">进行中</p>
          </div>
          <div class="card-base text-center py-4">
            <p class="text-2xl font-bold text-gray-400">{{ closedCount }}</p>
            <p class="text-base text-gray-500 mt-1">已关闭</p>
          </div>
        </div>

        <div class="flex gap-2 bg-white p-1.5 rounded-xl shadow-soft">
          <button
            v-for="tab in tabs"
            :key="tab.key"
            @click="activeTab = tab.key"
            :class="[
              'flex-1 py-3 rounded-lg text-base font-medium transition-all',
              activeTab === tab.key
                ? 'bg-gradient-primary text-white shadow-soft'
                : 'text-gray-500 hover:text-gray-700'
            ]"
          >
            {{ tab.label }}
          </button>
        </div>
      </div>
    </div>

    <div class="px-5 pt-4">
      <div class="space-y-4 pb-6">
        <div 
          v-for="(reminder, index) in filteredReminders" 
          :key="reminder.id"
          class="card-base animate-slide-up"
          :style="{ animationDelay: `${index * 50}ms` }"
          :class="{ 'opacity-60': !reminder.enabled }"
        >
          <div class="flex items-start gap-4">
            <div 
              :class="[
                'w-14 h-14 rounded-2xl flex items-center justify-center flex-shrink-0',
                typeConfig[reminder.type]?.bg
              ]"
            >
              <component :is="typeConfig[reminder.type]?.icon" :size="28" :class="typeConfig[reminder.type]?.color" />
            </div>
            <div class="flex-1 min-w-0">
              <div class="flex items-center justify-between mb-2">
                <p 
                  :class="[
                    'text-lg font-semibold flex-1',
                    !reminder.enabled ? 'text-gray-400 line-through' : 'text-gray-800'
                  ]"
                >
                  {{ reminder.title }}
                </p>
                <el-switch 
                  v-model="reminder.enabled"
                  size="large"
                  active-color="#FF8C42"
                  @change="(val: boolean) => handleToggle(reminder.id, val)"
                />
              </div>
              <p v-if="reminder.description && reminder.enabled" class="text-base text-gray-400 mb-3 line-clamp-1">
                {{ reminder.description }}
              </p>
              <div class="flex items-center gap-2 flex-wrap">
                <span 
                  :class="[
                    'inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-sm font-medium',
                    typeConfig[reminder.type]?.bg,
                    typeConfig[reminder.type]?.color
                  ]"
                >
                  <component :is="typeConfig[reminder.type]?.icon" :size="14" />
                  {{ typeConfig[reminder.type]?.label }}
                </span>
                <span class="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-sm font-medium bg-blue-50 text-blue-500">
                  <Calendar :size="14" />
                  {{ formatDate(reminder.date) }}
                  <span v-if="reminder.time">{{ reminder.time }}</span>
                </span>
                <span 
                  v-if="reminder.repeat && reminder.repeat !== 'none'"
                  class="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-sm font-medium bg-purple-50 text-purple-500"
                >
                  <Clock3 :size="14" />
                  {{ getRepeatLabel(reminder.repeat) }}
                </span>
              </div>
            </div>
          </div>
          <div class="flex justify-between items-center mt-4 pt-4 border-t border-gray-50">
            <button 
              v-if="reminder.enabled"
              @click="handleSnooze(reminder.id)"
              class="flex items-center gap-2 text-base text-primary-500 hover:text-primary-600 transition-colors px-3 py-2 rounded-lg hover:bg-primary-50"
            >
              <Clock :size="18" />
              稍后提醒(5分钟)
            </button>
            <div v-else></div>
            <button 
              @click="handleDelete(reminder.id)"
              class="text-base text-red-400 hover:text-red-500 transition-colors px-3 py-2 rounded-lg hover:bg-red-50"
            >
              删除
            </button>
          </div>
        </div>
      </div>

      <div v-if="!reminderStore.loading && filteredReminders.length === 0" class="text-center py-12">
        <div class="text-6xl mb-4">🔔</div>
        <p class="text-gray-500 text-lg mb-2">
          {{ activeTab === 'closed' ? '还没有关闭的提醒' : activeTab === 'active' ? '太棒了，没有进行中的提醒！' : '暂无提醒' }}
        </p>
        <p class="text-gray-400 text-base">点击右上角添加新提醒</p>
      </div>
    </div>

    <Teleport to="body">
      <Transition name="fade">
        <div v-if="showDialog" class="fixed inset-0 bg-black/50 z-50 flex items-end sm:items-center justify-center" @click.self="showDialog = false">
          <div class="bg-white w-full sm:max-w-lg sm:rounded-3xl rounded-t-3xl p-6 animate-slide-up max-h-[90vh] overflow-y-auto">
            <div class="flex items-center justify-between mb-6">
              <h3 class="text-xl font-bold text-gray-800">
                {{ isEditing ? '编辑提醒' : '添加提醒' }}
              </h3>
              <button 
                @click="showDialog = false"
                class="w-10 h-10 rounded-full bg-gray-100 flex items-center justify-center hover:bg-gray-200 transition-colors"
              >
                <X :size="20" class="text-gray-500" />
              </button>
            </div>

            <div class="space-y-5">
              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">提醒标题</label>
                <input 
                  v-model="formData.title"
                  type="text" 
                  placeholder="输入提醒标题" 
                  class="input-base text-lg py-4"
                />
              </div>

              <div class="grid grid-cols-2 gap-4">
                <div>
                  <label class="block text-base font-medium text-gray-700 mb-3">日期</label>
                  <input 
                    v-model="formData.date"
                    type="date" 
                    class="input-base text-base py-3"
                  />
                </div>
                <div>
                  <label class="block text-base font-medium text-gray-700 mb-3">时间</label>
                  <input 
                    v-model="formData.time"
                    type="time" 
                    class="input-base text-base py-3"
                  />
                </div>
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">类型</label>
                <div class="grid grid-cols-3 gap-3">
                  <button 
                    v-for="(config, key) in typeConfig" 
                    :key="key"
                    @click="formData.type = key as ReminderType"
                    :class="[
                      'py-3 rounded-xl text-base font-medium transition-all flex flex-col items-center gap-1',
                      formData.type === key 
                        ? `${config.bg} ${config.color} ring-2 ${config.borderColor.replace('border-', 'ring-')}` 
                        : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                    ]"
                  >
                    <component :is="config.icon" :size="22" />
                    <span class="text-sm">{{ config.label }}</span>
                  </button>
                </div>
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">重复</label>
                <div class="grid grid-cols-4 gap-2">
                  <button
                    v-for="option in repeatOptions"
                    :key="option.value"
                    @click="formData.repeat = option.value as ReminderRepeat"
                    :class="[
                      'py-3 rounded-xl text-base font-medium transition-all',
                      formData.repeat === option.value
                        ? 'bg-purple-100 text-purple-600 ring-2 ring-purple-200'
                        : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                    ]"
                  >
                    {{ option.label }}
                  </button>
                </div>
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">备注</label>
                <textarea 
                  v-model="formData.description"
                  placeholder="添加备注（可选）" 
                  class="input-base min-h-[80px] resize-none text-base"
                  rows="2"
                ></textarea>
              </div>
            </div>

            <div class="flex gap-3 mt-8">
              <button 
                @click="showDialog = false"
                class="flex-1 py-4 rounded-xl border-2 border-gray-200 text-gray-600 text-lg font-medium hover:bg-gray-50 transition-colors"
              >
                取消
              </button>
              <button 
                @click="handleSubmit"
                class="flex-1 btn-primary text-lg py-4"
              >
                {{ isEditing ? '保存' : '添加' }}
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
</style>
