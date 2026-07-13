<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { CheckSquare, Plus, Clock, Flag, X, Calendar, Tag, Mic, Play, Pause, Volume2, Type, Bell, Repeat, Loader2 } from 'lucide-vue-next'
import { useTodoStore } from '@/stores/todo'
import { ElMessage, ElMessageBox, ElSwitch } from 'element-plus'
import type { TodoPriority } from '@/types'
import { dayjs } from '@/utils'
import { speechApi } from '@/api'

const todoStore = useTodoStore()

const activeTab = ref<'all' | 'active' | 'completed'>('all')
const showDialog = ref(false)
const isEditing = ref(false)

const contentType = ref<'text' | 'voice'>('text')
const isRecording = ref(false)
const isRecognizing = ref(false)
const recordingDuration = ref(0)
let recordingTimer: number | null = null
let recordedBlob: Blob | null = null

const isPlaying = ref(false)
const playingTodoId = ref<string | number | null>(null)

const formData = ref({
  title: '',
  description: '',
  priority: 'medium' as TodoPriority,
  dueDate: dayjs().format('YYYY-MM-DD'),
  dueTime: '',
  category: '工作',
  contentType: 'text' as 'text' | 'voice',
  voiceUrl: '',
  voiceDuration: 0,
  hasReminder: false,
  remindTimeType: 'absolute' as 'absolute' | 'relative',
  remindRelativeMinutes: 30,
  repeatType: 'none' as 'none' | 'daily' | 'weekly' | 'monthly',
})

const tabs = [
  { key: 'all' as const, label: '全部' },
  { key: 'active' as const, label: '进行中' },
  { key: 'completed' as const, label: '已完成' },
]

const priorityConfig: Record<string, { color: string; bg: string; label: string; borderColor: string }> = {
  high: { color: 'text-red-500', bg: 'bg-red-50', label: '高优先级', borderColor: 'border-red-200' },
  medium: { color: 'text-yellow-600', bg: 'bg-yellow-50', label: '中优先级', borderColor: 'border-yellow-200' },
  low: { color: 'text-green-500', bg: 'bg-green-50', label: '低优先级', borderColor: 'border-green-200' },
}

const categories = ['工作', '生活', '学习', '健康', '其他']

const relativeTimeOptions = [
  { value: 30, label: '30分钟后' },
  { value: 60, label: '1小时后' },
  { value: 120, label: '2小时后' },
]

const repeatOptions = [
  { value: 'none', label: '不重复' },
  { value: 'daily', label: '每天' },
  { value: 'weekly', label: '每周' },
  { value: 'monthly', label: '每月' },
]

onMounted(async () => {
  await Promise.all([
    todoStore.fetchTodos({ page: 1, pageSize: 50 }),
    todoStore.fetchStats(),
  ])
})

const filteredTodos = computed(() => {
  if (activeTab.value === 'active') {
    return todoStore.todos.filter(t => !t.completed)
  }
  if (activeTab.value === 'completed') {
    return todoStore.todos.filter(t => t.completed)
  }
  return todoStore.todos
})

const formatDueDate = (date?: string) => {
  if (!date) return ''
  const d = dayjs(date)
  if (d.isSame(dayjs(), 'day')) return '今天'
  if (d.isSame(dayjs().add(1, 'day'), 'day')) return '明天'
  if (d.isSame(dayjs().subtract(1, 'day'), 'day')) return '昨天'
  return d.format('M月D日')
}

const isOverdue = (todo: any) => {
  if (todo.completed || !todo.dueDate) return false
  return dayjs(todo.dueDate).isBefore(dayjs(), 'day')
}

const formatDuration = (seconds: number) => {
  const mins = Math.floor(seconds / 60)
  const secs = seconds % 60
  return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
}

const handleToggle = async (id: string | number, completed: boolean) => {
  try {
    await todoStore.toggleTodo(id, completed)
  } catch (e) {
    console.error(e)
  }
}

const openAddDialog = () => {
  isEditing.value = false
  contentType.value = 'text'
  isRecording.value = false
  recordingDuration.value = 0
  formData.value = {
    title: '',
    description: '',
    priority: 'medium',
    dueDate: dayjs().format('YYYY-MM-DD'),
    dueTime: '',
    category: '工作',
    contentType: 'text',
    voiceUrl: '',
    voiceDuration: 0,
    hasReminder: false,
    remindTimeType: 'absolute',
    remindRelativeMinutes: 30,
    repeatType: 'none',
  }
  showDialog.value = true
}

let mediaRecorder: MediaRecorder | null = null
let audioChunks: Blob[] = []

const startRecording = async () => {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    mediaRecorder = new MediaRecorder(stream)
    audioChunks = []

    mediaRecorder.ondataavailable = (event) => {
      audioChunks.push(event.data)
    }

    mediaRecorder.onstop = () => {
      recordedBlob = new Blob(audioChunks, { type: 'audio/webm' })
      stream.getTracks().forEach(track => track.stop())
    }

    mediaRecorder.start()
    isRecording.value = true
    recordingDuration.value = 0
    recordingTimer = window.setInterval(() => {
      recordingDuration.value++
    }, 1000)
  } catch (e) {
    console.error('录音失败:', e)
    ElMessage.warning('无法访问麦克风，将使用模拟录音')
    isRecording.value = true
    recordingDuration.value = 0
    recordingTimer = window.setInterval(() => {
      recordingDuration.value++
    }, 1000)
  }
}

const stopRecording = async () => {
  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    mediaRecorder.stop()
  }
  isRecording.value = false
  if (recordingTimer) {
    clearInterval(recordingTimer)
    recordingTimer = null
  }
  formData.value.voiceDuration = recordingDuration.value
  formData.value.contentType = 'voice'

  if (recordedBlob) {
    formData.value.voiceUrl = URL.createObjectURL(recordedBlob)
  }

  if (recordingDuration.value > 0) {
    await recognizeSpeech()
  }
}

const recognizeSpeech = async () => {
  if (!recordedBlob) {
    formData.value.title = `语音待办 (${formatDuration(recordingDuration.value)})`
    return
  }

  isRecognizing.value = true
  try {
    const file = new File([recordedBlob], 'recording.webm', { type: 'audio/webm' })
    const result = await speechApi.recognize(file)
    if (result.text) {
      formData.value.title = result.text
    } else {
      formData.value.title = `语音待办 (${formatDuration(recordingDuration.value)})`
      ElMessage.warning('未识别到语音内容，可手动输入')
    }
  } catch (e) {
    console.error('语音识别失败:', e)
    formData.value.title = `语音待办 (${formatDuration(recordingDuration.value)})`
    ElMessage.warning('语音识别失败，可手动输入')
  } finally {
    isRecognizing.value = false
  }
}

const playVoice = (todo: any) => {
  if (isPlaying.value && playingTodoId.value === todo.id) {
    isPlaying.value = false
    playingTodoId.value = null
  } else {
    isPlaying.value = true
    playingTodoId.value = todo.id
    setTimeout(() => {
      isPlaying.value = false
      playingTodoId.value = null
    }, (todo.voiceDuration || 0) * 1000)
  }
}

const handleSubmit = async () => {
  if (formData.value.contentType === 'text' && !formData.value.title.trim()) {
    ElMessage.warning('请输入待办标题')
    return
  }
  if (formData.value.contentType === 'voice' && !formData.value.voiceDuration) {
    ElMessage.warning('请录制语音')
    return
  }
  try {
    if (isEditing.value) {
    } else {
      await todoStore.createTodo(formData.value)
      ElMessage.success('添加成功')
    }
    showDialog.value = false
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

const handleDelete = async (id: string | number) => {
  try {
    await ElMessageBox.confirm('确定要删除这个待办吗？', '删除待办', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await todoStore.deleteTodo(id)
    ElMessage.success('删除成功')
  } catch {
  }
}

const getRepeatLabel = (type: string) => {
  const option = repeatOptions.find(o => o.value === type)
  return option?.label || ''
}
</script>

<template>
  <div class="page-container">
    <div class="safe-area-top bg-gradient-to-b from-orange-100 to-transparent pb-4">
      <div class="px-5 pt-4">
        <div class="flex items-center justify-between mb-6">
          <h1 class="text-2xl font-bold text-gray-800">待办清单</h1>
          <button 
            @click="openAddDialog"
            class="w-12 h-12 rounded-full bg-gradient-primary flex items-center justify-center text-white shadow-card hover:scale-105 transition-transform active:scale-95"
          >
            <Plus :size="26" />
          </button>
        </div>

        <div class="grid grid-cols-3 gap-3 mb-6">
          <div class="card-base text-center py-4">
            <p class="text-3xl font-bold text-gray-800">{{ todoStore.stats.total }}</p>
            <p class="text-base text-gray-500 mt-1">全部</p>
          </div>
          <div class="card-base text-center py-4">
            <p class="text-3xl font-bold text-primary-500">{{ todoStore.stats.active }}</p>
            <p class="text-base text-gray-500 mt-1">进行中</p>
          </div>
          <div class="card-base text-center py-4">
            <p class="text-3xl font-bold text-secondary-500">{{ todoStore.stats.completed }}</p>
            <p class="text-base text-gray-500 mt-1">已完成</p>
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
          v-for="(todo, index) in filteredTodos" 
          :key="todo.id"
          class="card-base animate-slide-up"
          :style="{ animationDelay: `${index * 50}ms` }"
          :class="{ 'opacity-60': todo.completed }"
        >
          <div class="flex items-start gap-4">
            <div 
              :class="[
                'w-7 h-7 rounded-full border-2 flex items-center justify-center cursor-pointer transition-all flex-shrink-0 mt-0.5',
                todo.completed 
                  ? 'bg-secondary-400 border-secondary-400' 
                  : 'border-gray-300 hover:border-primary-400'
              ]"
              @click="handleToggle(todo.id, !todo.completed)"
            >
              <CheckSquare v-if="todo.completed" :size="16" class="text-white" />
            </div>
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 mb-2">
                <p 
                  :class="[
                    'text-lg font-semibold flex-1',
                    todo.completed ? 'text-gray-400 line-through' : 'text-gray-800'
                  ]"
                >
                  {{ todo.title }}
                </p>
                <button 
                  v-if="todo.contentType === 'voice'"
                  @click.stop="playVoice(todo)"
                  :class="[
                    'w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0 transition-all',
                    isPlaying && playingTodoId === todo.id
                      ? 'bg-primary-100 text-primary-600'
                      : 'bg-primary-50 text-primary-500 hover:bg-primary-100'
                  ]"
                >
                  <Pause v-if="isPlaying && playingTodoId === todo.id" :size="18" />
                  <Play v-else :size="18" />
                </button>
              </div>
              <p v-if="todo.description && !todo.completed" class="text-base text-gray-400 mb-3 line-clamp-2">
                {{ todo.description }}
              </p>
              <div v-if="todo.contentType === 'voice' && todo.voiceDuration" class="flex items-center gap-2 mb-3 text-primary-500">
                <Volume2 :size="16" />
                <span class="text-sm">语音时长 {{ formatDuration(todo.voiceDuration) }}</span>
              </div>
              <div class="flex items-center gap-2 flex-wrap">
                <span 
                  :class="[
                    'inline-flex items-center gap-1 px-3 py-1 rounded-full text-sm font-medium',
                    priorityConfig[todo.priority]?.bg,
                    priorityConfig[todo.priority]?.color
                  ]"
                >
                  <Flag :size="12" />
                  {{ priorityConfig[todo.priority]?.label }}
                </span>
                <span 
                  v-if="todo.category"
                  class="inline-flex items-center gap-1 px-3 py-1 rounded-full text-sm font-medium bg-gray-50 text-gray-500"
                >
                  <Tag :size="12" />
                  {{ todo.category }}
                </span>
                <span 
                  v-if="todo.dueDate"
                  :class="[
                    'inline-flex items-center gap-1 px-3 py-1 rounded-full text-sm font-medium',
                    isOverdue(todo) ? 'bg-red-50 text-red-500' : 'bg-blue-50 text-blue-500'
                  ]"
                >
                  <Calendar :size="12" />
                  {{ formatDueDate(todo.dueDate) }}
                  <span v-if="todo.dueTime">{{ todo.dueTime }}</span>
                </span>
                <span 
                  v-if="todo.hasReminder"
                  class="inline-flex items-center gap-1 px-3 py-1 rounded-full text-sm font-medium bg-orange-50 text-orange-500"
                >
                  <Bell :size="12" />
                  提醒
                </span>
                <span 
                  v-if="todo.repeatType && todo.repeatType !== 'none'"
                  class="inline-flex items-center gap-1 px-3 py-1 rounded-full text-sm font-medium bg-purple-50 text-purple-500"
                >
                  <Repeat :size="12" />
                  {{ getRepeatLabel(todo.repeatType) }}
                </span>
              </div>
            </div>
          </div>
          <div class="flex justify-end mt-4 pt-4 border-t border-gray-50">
            <button 
              @click="handleDelete(todo.id)"
              class="text-sm text-red-400 hover:text-red-500 transition-colors"
            >
              删除
            </button>
          </div>
        </div>
      </div>

      <div v-if="!todoStore.loading && filteredTodos.length === 0" class="text-center py-12">
        <div class="text-6xl mb-4">✅</div>
        <p class="text-gray-500 text-lg mb-2">
          {{ activeTab === 'completed' ? '还没有完成的待办' : activeTab === 'active' ? '太棒了，没有待办！' : '暂无待办' }}
        </p>
        <p class="text-gray-400 text-base">点击右上角添加新待办</p>
      </div>
    </div>

    <Teleport to="body">
      <Transition name="fade">
        <div v-if="showDialog" class="fixed inset-0 bg-black/50 z-50 flex items-end sm:items-center justify-center" @click.self="showDialog = false">
          <div class="bg-white w-full sm:max-w-lg sm:rounded-3xl rounded-t-3xl p-6 animate-slide-up max-h-[90vh] overflow-y-auto">
            <div class="flex items-center justify-between mb-6">
              <h3 class="text-xl font-bold text-gray-800">
                {{ isEditing ? '编辑待办' : '添加待办' }}
              </h3>
              <button 
                @click="showDialog = false"
                class="w-10 h-10 rounded-full bg-gray-100 flex items-center justify-center hover:bg-gray-200 transition-colors"
              >
                <X :size="20" class="text-gray-500" />
              </button>
            </div>

            <div class="flex gap-2 bg-cream-100 p-1.5 rounded-2xl mb-6">
              <button
                @click="contentType = 'text'; formData.contentType = 'text'"
                :class="[
                  'flex-1 py-3.5 rounded-xl text-base font-semibold transition-all flex items-center justify-center gap-2',
                  contentType === 'text'
                    ? 'bg-white text-primary-600 shadow-soft'
                    : 'text-gray-500 hover:text-gray-700'
                ]"
              >
                <Type :size="20" />
                文字
              </button>
              <button
                @click="contentType = 'voice'; formData.contentType = 'voice'"
                :class="[
                  'flex-1 py-3.5 rounded-xl text-base font-semibold transition-all flex items-center justify-center gap-2',
                  contentType === 'voice'
                    ? 'bg-white text-primary-600 shadow-soft'
                    : 'text-gray-500 hover:text-gray-700'
                ]"
              >
                <Mic :size="20" />
                语音
              </button>
            </div>

            <div v-show="contentType === 'text'" class="space-y-5">
              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">待办标题</label>
                <input 
                  v-model="formData.title"
                  type="text" 
                  placeholder="输入待办标题" 
                  class="input-base text-lg py-4"
                />
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">描述</label>
                <textarea 
                  v-model="formData.description"
                  placeholder="添加详细描述（可选）" 
                  class="input-base min-h-[100px] resize-none text-base"
                  rows="3"
                ></textarea>
              </div>
            </div>

            <div v-show="contentType === 'voice'" class="space-y-5">
              <div class="flex flex-col items-center py-8">
                <button
                  v-if="!isRecording && !isRecognizing"
                  @click="startRecording"
                  class="w-28 h-28 rounded-full bg-gradient-primary flex items-center justify-center text-white shadow-float hover:scale-105 transition-transform active:scale-95 mb-4"
                >
                  <Mic :size="48" />
                </button>
                <button
                  v-else-if="isRecording"
                  @click="stopRecording"
                  class="w-28 h-28 rounded-full bg-red-500 flex items-center justify-center text-white shadow-float animate-pulse-soft mb-4"
                >
                  <div class="w-10 h-10 rounded bg-white"></div>
                </button>
                <div
                  v-else
                  class="w-28 h-28 rounded-full bg-primary-100 flex items-center justify-center text-primary-500 shadow-float mb-4"
                >
                  <Loader2 :size="48" class="animate-spin" />
                </div>
                <p class="text-2xl font-bold text-gray-800 mb-2">
                  {{ formatDuration(recordingDuration) }}
                </p>
                <p class="text-base text-gray-500">
                  <template v-if="isRecording">正在录音，点击结束</template>
                  <template v-else-if="isRecognizing">正在识别语音...</template>
                  <template v-else>点击开始录音</template>
                </p>
                <p v-if="formData.voiceDuration && !isRecording && !isRecognizing" class="text-base text-primary-500 mt-2">
                  已录制 {{ formatDuration(formData.voiceDuration) }}
                </p>
              </div>

              <div v-if="formData.voiceDuration && !isRecording" class="space-y-4">
                <div>
                  <label class="block text-base font-medium text-gray-700 mb-3">识别结果</label>
                  <input 
                    v-model="formData.title"
                    type="text" 
                    placeholder="语音识别结果，可手动修改" 
                    class="input-base text-lg py-4"
                    :disabled="isRecognizing"
                  />
                </div>
                <div>
                  <label class="block text-base font-medium text-gray-700 mb-3">描述（可选）</label>
                  <textarea 
                    v-model="formData.description"
                    placeholder="添加详细描述（可选）" 
                    class="input-base min-h-[80px] resize-none text-base"
                    rows="2"
                  ></textarea>
                </div>
              </div>
            </div>

            <div class="mt-6 pt-6 border-t border-gray-100 space-y-5">
              <div class="flex items-center justify-between">
                <div class="flex items-center gap-3">
                  <div class="w-10 h-10 rounded-full bg-orange-100 flex items-center justify-center">
                    <Bell :size="20" class="text-orange-500" />
                  </div>
                  <div>
                    <p class="text-base font-medium text-gray-800">提醒</p>
                    <p class="text-sm text-gray-400">设置提醒时间</p>
                  </div>
                </div>
                <el-switch 
                  v-model="formData.hasReminder" 
                  size="large"
                  active-color="#FF8C42"
                />
              </div>

              <div v-if="formData.hasReminder" class="space-y-4 pl-13">
                <div class="flex gap-3">
                  <button
                    @click="formData.remindTimeType = 'absolute'"
                    :class="[
                      'flex-1 py-3 rounded-xl text-base font-medium transition-all',
                      formData.remindTimeType === 'absolute'
                        ? 'bg-primary-100 text-primary-600 ring-2 ring-primary-200'
                        : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                    ]"
                  >
                    绝对时间
                  </button>
                  <button
                    @click="formData.remindTimeType = 'relative'"
                    :class="[
                      'flex-1 py-3 rounded-xl text-base font-medium transition-all',
                      formData.remindTimeType === 'relative'
                        ? 'bg-primary-100 text-primary-600 ring-2 ring-primary-200'
                        : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                    ]"
                  >
                    相对时间
                  </button>
                </div>

                <div v-if="formData.remindTimeType === 'absolute'" class="grid grid-cols-2 gap-4">
                  <div>
                    <label class="block text-sm font-medium text-gray-600 mb-2">日期</label>
                    <input 
                      v-model="formData.dueDate"
                      type="date" 
                      class="input-base text-base"
                    />
                  </div>
                  <div>
                    <label class="block text-sm font-medium text-gray-600 mb-2">时间</label>
                    <input 
                      v-model="formData.dueTime"
                      type="time" 
                      class="input-base text-base"
                    />
                  </div>
                </div>

                <div v-if="formData.remindTimeType === 'relative'" class="grid grid-cols-3 gap-3">
                  <button
                    v-for="option in relativeTimeOptions"
                    :key="option.value"
                    @click="formData.remindRelativeMinutes = option.value"
                    :class="[
                      'py-3 rounded-xl text-base font-medium transition-all',
                      formData.remindRelativeMinutes === option.value
                        ? 'bg-primary-100 text-primary-600 ring-2 ring-primary-200'
                        : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                    ]"
                  >
                    {{ option.label }}
                  </button>
                </div>
              </div>

              <div class="flex items-center justify-between pt-2">
                <div class="flex items-center gap-3">
                  <div class="w-10 h-10 rounded-full bg-purple-100 flex items-center justify-center">
                    <Repeat :size="20" class="text-purple-500" />
                  </div>
                  <div>
                    <p class="text-base font-medium text-gray-800">重复</p>
                    <p class="text-sm text-gray-400">设置重复周期</p>
                  </div>
                </div>
              </div>

              <div class="grid grid-cols-4 gap-2 pl-13">
                <button
                  v-for="option in repeatOptions"
                  :key="option.value"
                  @click="formData.repeatType = option.value as any"
                  :class="[
                    'py-2.5 rounded-xl text-sm font-medium transition-all',
                    formData.repeatType === option.value
                      ? 'bg-purple-100 text-purple-600 ring-2 ring-purple-200'
                      : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                  ]"
                >
                  {{ option.label }}
                </button>
              </div>

              <div class="pt-2">
                <label class="block text-base font-medium text-gray-700 mb-3">优先级</label>
                <div class="grid grid-cols-3 gap-3">
                  <button 
                    v-for="(config, key) in priorityConfig" 
                    :key="key"
                    @click="formData.priority = key as TodoPriority"
                    :class="[
                      'py-3.5 rounded-xl text-base font-medium transition-all flex items-center justify-center gap-2',
                      formData.priority === key 
                        ? `${config.bg} ${config.color} ring-2 ${config.borderColor.replace('border-', 'ring-')}` 
                        : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                    ]"
                  >
                    <Flag :size="16" />
                    {{ config.label.replace('优先级', '') }}
                  </button>
                </div>
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">分类</label>
                <div class="flex gap-2 flex-wrap">
                  <button 
                    v-for="cat in categories" 
                    :key="cat"
                    @click="formData.category = cat"
                    :class="[
                      'px-5 py-3 rounded-full text-base font-medium transition-all',
                      formData.category === cat 
                        ? 'bg-primary-100 text-primary-600 ring-2 ring-primary-200' 
                        : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                    ]"
                  >
                    {{ cat }}
                  </button>
                </div>
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
.pl-13 {
  padding-left: 3.25rem;
}
</style>
