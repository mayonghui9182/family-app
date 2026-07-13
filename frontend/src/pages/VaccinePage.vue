<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { 
  ArrowLeft, Syringe, Calendar, Clock, CheckCircle, 
  X, Bell, BellOff, Edit, SkipForward, ChevronRight,
  AlertCircle, Plus, Shield
} from 'lucide-vue-next'
import { ElMessage, ElMessageBox } from 'element-plus'
import { vaccineApi } from '@/api'
import { useBabyStore } from '@/stores/baby'
import { dayjs } from '@/utils'
import type { BabyVaccine, VaccineStats } from '@/types'
import { generateMockVaccines, generateMockVaccineStats, mockBaby } from '@/mock'

const route = useRoute()
const router = useRouter()
const babyStore = useBabyStore()

const babyId = computed(() => {
  const id = route.params.id
  return Array.isArray(id) ? id[0] : id || '1'
})
const activeTab = ref<'pending' | 'completed' | 'all'>('pending')
const loading = ref(false)
const vaccines = ref<BabyVaccine[]>([])
const stats = ref<VaccineStats | null>(null)

const showVaccinateDialog = ref(false)
const showDateDialog = ref(false)
const showDetailDialog = ref(false)
const currentVaccine = ref<BabyVaccine | null>(null)

const vaccinateForm = ref({
  actualDate: dayjs().format('YYYY-MM-DD'),
  hospital: '',
  batchNumber: '',
  injectionSite: '',
  adverseReaction: '',
  remark: '',
})

const dateForm = ref({
  plannedDate: '',
})

const tabs = [
  { key: 'pending' as const, label: '待接种' },
  { key: 'completed' as const, label: '已接种' },
  { key: 'all' as const, label: '全部' },
]

const filteredVaccines = computed(() => {
  if (activeTab.value === 'pending') {
    return vaccines.value.filter(v => v.status === 'pending' || v.status === 'delayed')
      .sort((a, b) => new Date(a.plannedDate).getTime() - new Date(b.plannedDate).getTime())
  }
  if (activeTab.value === 'completed') {
    return vaccines.value.filter(v => v.status === 'completed')
      .sort((a, b) => new Date(b.actualDate || b.plannedDate).getTime() - new Date(a.actualDate || a.plannedDate).getTime())
  }
  return [...vaccines.value]
    .sort((a, b) => new Date(a.plannedDate).getTime() - new Date(b.plannedDate).getTime())
})

const babyInfo = computed(() => babyStore.babyInfo)
const ageText = computed(() => babyStore.ageText)

const hasData = computed(() => vaccines.value.length > 0)

onMounted(async () => {
  await babyStore.fetchBabyInfo(babyId.value)
  await fetchVaccineData()
})

async function fetchVaccineData() {
  loading.value = true
  try {
    const [vaccineList, vaccineStats] = await Promise.all([
      vaccineApi.getBabyVaccines(babyId.value),
      vaccineApi.getVaccineStats(babyId.value),
    ])
    vaccines.value = vaccineList
    stats.value = vaccineStats
  } catch (e) {
    console.warn('使用mock疫苗数据:', e)
    const birthDate = babyStore.babyInfo?.birthDate || mockBaby.birthDate
    const mockVaccines = generateMockVaccines(birthDate)
    vaccines.value = mockVaccines
    stats.value = generateMockVaccineStats(mockVaccines)
  } finally {
    loading.value = false
  }
}

function formatDate(date: string) {
  return dayjs(date).format('YYYY年M月D日')
}

function formatShortDate(date: string) {
  return dayjs(date).format('M月D日')
}

function getStatusText(vaccine: BabyVaccine) {
  switch (vaccine.status) {
    case 'completed': return '已接种'
    case 'skipped': return '已跳过'
    case 'delayed': return '已逾期'
    default:
      if (vaccine.isOverdue) return '已逾期'
      if (vaccine.daysLeft === 0) return '今天接种'
      if (vaccine.daysLeft > 0) return `还有${vaccine.daysLeft}天`
      return '待接种'
  }
}

function getStatusClass(vaccine: BabyVaccine) {
  if (vaccine.status === 'completed') return 'text-green-500 bg-green-50'
  if (vaccine.status === 'skipped') return 'text-gray-500 bg-gray-50'
  if (vaccine.isOverdue || vaccine.status === 'delayed') return 'text-red-500 bg-red-50'
  return 'text-blue-500 bg-blue-50'
}

function openVaccinateDialog(vaccine: BabyVaccine) {
  currentVaccine.value = vaccine
  vaccinateForm.value = {
    actualDate: dayjs().format('YYYY-MM-DD'),
    hospital: '',
    batchNumber: '',
    injectionSite: '',
    adverseReaction: '',
    remark: '',
  }
  showVaccinateDialog.value = true
}

async function handleMarkVaccinated() {
  if (!currentVaccine.value) return
  try {
    await vaccineApi.markVaccinated(babyId.value, currentVaccine.value.id, {
      actualDate: vaccinateForm.value.actualDate,
      hospital: vaccinateForm.value.hospital,
      batchNumber: vaccinateForm.value.batchNumber,
      injectionSite: vaccinateForm.value.injectionSite,
      adverseReaction: vaccinateForm.value.adverseReaction,
      remark: vaccinateForm.value.remark,
    })
    ElMessage.success('已标记为接种')
    showVaccinateDialog.value = false
    await fetchVaccineData()
  } catch (e) {
    console.warn('使用mock标记接种:', e)
    const index = vaccines.value.findIndex(v => v.id === currentVaccine.value?.id)
    if (index !== -1) {
      vaccines.value[index].status = 'completed'
      vaccines.value[index].actualDate = vaccinateForm.value.actualDate
      vaccines.value[index].hospital = vaccinateForm.value.hospital
      vaccines.value[index].batchNumber = vaccinateForm.value.batchNumber
      vaccines.value[index].injectionSite = vaccinateForm.value.injectionSite
      vaccines.value[index].adverseReaction = vaccinateForm.value.adverseReaction
      vaccines.value[index].remark = vaccinateForm.value.remark
      stats.value = generateMockVaccineStats(vaccines.value)
    }
    ElMessage.success('已标记为接种')
    showVaccinateDialog.value = false
  }
}

function openDateDialog(vaccine: BabyVaccine) {
  currentVaccine.value = vaccine
  dateForm.value.plannedDate = vaccine.plannedDate
  showDateDialog.value = true
}

async function handleUpdateDate() {
  if (!currentVaccine.value) return
  try {
    await vaccineApi.updatePlannedDate(babyId.value, currentVaccine.value.id, dateForm.value.plannedDate)
    ElMessage.success('日期已更新')
    showDateDialog.value = false
    await fetchVaccineData()
  } catch (e) {
    console.warn('使用mock更新日期:', e)
    const index = vaccines.value.findIndex(v => v.id === currentVaccine.value?.id)
    if (index !== -1) {
      vaccines.value[index].plannedDate = dateForm.value.plannedDate
      const today = dayjs()
      const planned = dayjs(dateForm.value.plannedDate)
      vaccines.value[index].daysLeft = planned.diff(today, 'day')
      vaccines.value[index].isOverdue = planned.diff(today, 'day') < 0
      if (vaccines.value[index].status === 'pending' && planned.diff(today, 'day') < 0) {
        vaccines.value[index].status = 'delayed'
      } else if (vaccines.value[index].status === 'delayed' && planned.diff(today, 'day') >= 0) {
        vaccines.value[index].status = 'pending'
      }
      stats.value = generateMockVaccineStats(vaccines.value)
    }
    ElMessage.success('日期已更新')
    showDateDialog.value = false
  }
}

async function handleSkipVaccine(vaccine: BabyVaccine) {
  try {
    const { value: reason } = await ElMessageBox.prompt('请输入跳过原因', '跳过疫苗', {
      confirmButtonText: '确认跳过',
      cancelButtonText: '取消',
      inputPlaceholder: '请输入原因',
      type: 'warning',
    })
    if (reason) {
      try {
        await vaccineApi.skipVaccine(babyId.value, vaccine.id, reason)
        ElMessage.success('已跳过')
        await fetchVaccineData()
      } catch (e) {
        console.warn('使用mock跳过疫苗:', e)
        const index = vaccines.value.findIndex(v => v.id === vaccine.id)
        if (index !== -1) {
          vaccines.value[index].status = 'skipped'
          vaccines.value[index].remark = reason
          stats.value = generateMockVaccineStats(vaccines.value)
        }
        ElMessage.success('已跳过')
      }
    }
  } catch {
  }
}

async function handleToggleRemind(vaccine: BabyVaccine) {
  const newEnabled = vaccine.remindEnabled === 1 ? 0 : 1
  try {
    await vaccineApi.toggleRemind(babyId.value, vaccine.id, newEnabled)
    vaccine.remindEnabled = newEnabled
    ElMessage.success(newEnabled === 1 ? '已开启提醒' : '已关闭提醒')
  } catch (e) {
    console.warn('使用mock切换提醒:', e)
    const index = vaccines.value.findIndex(v => v.id === vaccine.id)
    if (index !== -1) {
      vaccines.value[index].remindEnabled = newEnabled
    }
    ElMessage.success(newEnabled === 1 ? '已开启提醒' : '已关闭提醒')
  }
}

function openDetailDialog(vaccine: BabyVaccine) {
  currentVaccine.value = vaccine
  showDetailDialog.value = true
}

async function handleGeneratePlan() {
  try {
    await vaccineApi.generatePlan(babyId.value)
    ElMessage.success('已生成疫苗计划')
    await fetchVaccineData()
  } catch (e) {
    console.warn('使用mock生成计划:', e)
    const birthDate = babyStore.babyInfo?.birthDate || mockBaby.birthDate
    const mockVaccines = generateMockVaccines(birthDate)
    vaccines.value = mockVaccines
    stats.value = generateMockVaccineStats(mockVaccines)
    ElMessage.success('已生成疫苗计划')
  }
}

function goBack() {
  router.back()
}
</script>

<template>
  <div class="page-container">
    <div class="safe-area-top bg-gradient-to-b from-green-100 to-transparent pb-4">
      <div class="px-5 pt-4">
        <div class="flex items-center gap-4 mb-6">
          <button 
            @click="goBack"
            class="w-12 h-12 rounded-full bg-white flex items-center justify-center shadow-soft hover:shadow-card transition-shadow"
          >
            <ArrowLeft :size="22" class="text-gray-700" />
          </button>
          <h1 class="text-2xl font-bold text-gray-800">疫苗接种</h1>
        </div>

        <div v-if="babyInfo" class="card-base bg-gradient-to-r from-green-400 to-green-500 text-white animate-slide-up">
          <div class="flex items-center gap-4">
            <div class="w-16 h-16 rounded-2xl bg-white/20 backdrop-blur-sm flex items-center justify-center">
              <Syringe :size="32" />
            </div>
            <div class="flex-1">
              <h2 class="text-xl font-bold mb-1">{{ babyInfo.name }}</h2>
              <p class="text-white/80 text-base">
                {{ babyInfo.gender === 'girl' ? '女宝宝' : '男宝宝' }} · {{ ageText }}
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="px-5">
      <div v-if="stats" class="grid grid-cols-1 gap-3 mb-6">
        <div class="card-base">
          <div class="flex items-center justify-between mb-3">
            <div class="flex items-center gap-2">
              <div class="w-10 h-10 rounded-lg bg-green-50 flex items-center justify-center">
                <CheckCircle :size="20" class="text-green-500" />
              </div>
              <span class="text-base font-medium text-gray-700">接种进度</span>
            </div>
            <span class="text-lg font-bold text-green-500">{{ stats.progress }}%</span>
          </div>
          <div class="w-full h-3 bg-gray-100 rounded-full overflow-hidden">
            <div 
              class="h-full bg-gradient-to-r from-green-400 to-green-500 rounded-full transition-all duration-500"
              :style="{ width: `${stats.progress}%` }"
            ></div>
          </div>
          <p class="text-sm text-gray-500 mt-2">
            已接种 {{ stats.completed }} 剂 / 共 {{ stats.total }} 剂
          </p>
        </div>

        <div class="grid grid-cols-2 gap-3">
          <div class="card-base">
            <div class="flex items-center gap-2 mb-2">
              <div class="w-10 h-10 rounded-lg bg-blue-50 flex items-center justify-center">
                <Calendar :size="20" class="text-blue-500" />
              </div>
              <span class="text-base text-gray-600">下一次接种</span>
            </div>
            <p class="text-lg font-bold text-gray-800 truncate">
              {{ stats.nextVaccineName || '--' }}
            </p>
            <p class="text-sm text-gray-500 mt-1">
              {{ stats.nextVaccineDate ? formatShortDate(stats.nextVaccineDate) : '--' }}
            </p>
          </div>

          <div class="card-base">
            <div class="flex items-center gap-2 mb-2">
              <div class="w-10 h-10 rounded-lg bg-red-50 flex items-center justify-center">
                <AlertCircle :size="20" class="text-red-500" />
              </div>
              <span class="text-base text-gray-600">逾期</span>
            </div>
            <p class="text-3xl font-bold" :class="stats.overdue > 0 ? 'text-red-500' : 'text-gray-400'">
              {{ stats.overdue }}
            </p>
            <p class="text-sm text-gray-500 mt-1">剂疫苗</p>
          </div>
        </div>
      </div>

      <div class="flex gap-2 bg-white p-1.5 rounded-xl shadow-soft mb-6">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          @click="activeTab = tab.key"
          :class="[
            'flex-1 py-3 rounded-xl text-base font-medium transition-all flex items-center justify-center gap-2',
            activeTab === tab.key
              ? 'bg-gradient-to-r from-green-400 to-green-500 text-white shadow-soft'
              : 'text-gray-500 hover:text-gray-700'
          ]"
        >
          {{ tab.label }}
        </button>
      </div>

      <div v-if="!hasData" class="py-16 text-center">
        <div class="w-24 h-24 mx-auto mb-6 rounded-full bg-green-50 flex items-center justify-center">
          <Syringe :size="40" class="text-green-300" />
        </div>
        <h3 class="text-xl font-bold text-gray-700 mb-2">暂无疫苗计划</h3>
        <p class="text-base text-gray-400 mb-8">点击下方按钮生成标准疫苗接种计划</p>
        <button 
          @click="handleGeneratePlan"
          class="btn-primary text-lg py-4 px-8 inline-flex items-center gap-2"
        >
          <Plus :size="22" />
          生成标准疫苗计划
        </button>
      </div>

      <div v-else class="space-y-4 pb-32">
        <div 
          v-for="(vaccine, index) in filteredVaccines" 
          :key="vaccine.id"
          class="card-base animate-slide-up"
          :class="{ 'ring-2 ring-red-300 bg-red-50/30': vaccine.isOverdue && vaccine.status !== 'completed' && vaccine.status !== 'skipped' }"
          :style="{ animationDelay: `${index * 30}ms` }"
        >
          <div class="flex items-start justify-between mb-3">
            <div class="flex-1">
              <div class="flex items-center gap-2 mb-1">
                <h4 class="text-lg font-bold text-gray-800">{{ vaccine.vaccineName }}</h4>
                <span 
                  :class="[
                    'px-2 py-0.5 rounded-full text-xs font-medium',
                    vaccine.type === 'free' ? 'bg-green-100 text-green-600' : 'bg-orange-100 text-orange-600'
                  ]"
                >
                  {{ vaccine.type === 'free' ? '免费' : '自费' }}
                </span>
              </div>
              <p class="text-base text-gray-500">
                第{{ vaccine.doseNumber }}剂 / 共{{ vaccine.totalDoses }}剂
              </p>
            </div>
            <span 
              :class="[
                'px-3 py-1 rounded-full text-sm font-medium flex-shrink-0',
                getStatusClass(vaccine)
              ]"
            >
              {{ getStatusText(vaccine) }}
            </span>
          </div>

          <div class="flex items-center gap-2 mb-3 text-base text-gray-600">
            <Calendar :size="16" class="text-gray-400" />
            <span>计划日期：{{ formatShortDate(vaccine.plannedDate) }}</span>
          </div>

          <div class="flex items-center gap-2 mb-4 text-base text-gray-600">
            <Shield :size="16" class="text-gray-400" />
            <span>预防：{{ vaccine.preventDisease }}</span>
          </div>

          <div v-if="vaccine.status === 'completed'" class="pt-3 border-t border-gray-100">
            <div class="grid grid-cols-2 gap-2 text-sm text-gray-500 mb-3">
              <div v-if="vaccine.actualDate">
                <span class="text-gray-400">接种日期：</span>
                <span>{{ formatShortDate(vaccine.actualDate) }}</span>
              </div>
              <div v-if="vaccine.hospital">
                <span class="text-gray-400">接种医院：</span>
                <span>{{ vaccine.hospital }}</span>
              </div>
            </div>
            <button 
              @click="openDetailDialog(vaccine)"
              class="w-full py-3 rounded-xl text-base font-medium bg-gray-50 text-gray-600 hover:bg-gray-100 transition-colors flex items-center justify-center gap-2"
            >
              查看详情
              <ChevronRight :size="18" />
            </button>
          </div>

          <div v-else-if="vaccine.status !== 'skipped'" class="pt-3 border-t border-gray-100">
            <div class="grid grid-cols-2 gap-2">
              <button 
                @click="openVaccinateDialog(vaccine)"
                class="py-3 rounded-xl text-base font-medium bg-gradient-to-r from-green-400 to-green-500 text-white hover:shadow-soft transition-all flex items-center justify-center gap-1"
              >
                <CheckCircle :size="18" />
                标记接种
              </button>
              <button 
                @click="openDateDialog(vaccine)"
                class="py-3 rounded-xl text-base font-medium bg-blue-50 text-blue-600 hover:bg-blue-100 transition-colors flex items-center justify-center gap-1"
              >
                <Edit :size="18" />
                修改日期
              </button>
              <button 
                @click="handleSkipVaccine(vaccine)"
                class="py-3 rounded-xl text-base font-medium bg-gray-50 text-gray-600 hover:bg-gray-100 transition-colors flex items-center justify-center gap-1"
              >
                <SkipForward :size="18" />
                跳过
              </button>
              <button 
                @click="handleToggleRemind(vaccine)"
                :class="[
                  'py-3 rounded-xl text-base font-medium transition-colors flex items-center justify-center gap-1',
                  vaccine.remindEnabled === 1 
                    ? 'bg-yellow-50 text-yellow-600 hover:bg-yellow-100' 
                    : 'bg-gray-50 text-gray-400 hover:bg-gray-100'
                ]"
              >
                <Bell v-if="vaccine.remindEnabled === 1" :size="18" />
                <BellOff v-else :size="18" />
                {{ vaccine.remindEnabled === 1 ? '提醒中' : '提醒' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div v-if="hasData && activeTab === 'pending'" class="fixed bottom-0 left-0 right-0 p-5 bg-gradient-to-t from-cream-100 via-cream-100 to-transparent pt-10 safe-area-bottom">
      <button 
        @click="handleGeneratePlan"
        class="w-full btn-secondary text-lg py-4 flex items-center justify-center gap-2"
      >
        <Plus :size="22" />
        重新生成标准疫苗计划
      </button>
    </div>

    <Teleport to="body">
      <Transition name="fade">
        <div v-if="showVaccinateDialog" class="fixed inset-0 bg-black/50 z-50 flex items-end sm:items-center justify-center" @click.self="showVaccinateDialog = false">
          <div class="bg-white w-full sm:max-w-lg sm:rounded-3xl rounded-t-3xl p-6 animate-slide-up max-h-[90vh] overflow-y-auto">
            <div class="flex items-center justify-between mb-6">
              <h3 class="text-xl font-bold text-gray-800">标记已接种</h3>
              <button 
                @click="showVaccinateDialog = false"
                class="w-10 h-10 rounded-full bg-gray-100 flex items-center justify-center hover:bg-gray-200 transition-colors"
              >
                <X :size="20" class="text-gray-500" />
              </button>
            </div>

            <div v-if="currentVaccine" class="mb-6 p-4 bg-green-50 rounded-xl">
              <p class="text-lg font-bold text-green-700">{{ currentVaccine.vaccineName }}</p>
              <p class="text-sm text-green-600">第{{ currentVaccine.doseNumber }}剂 / 共{{ currentVaccine.totalDoses }}剂</p>
            </div>

            <div class="space-y-5">
              <div>
                <label class="block text-base font-medium text-gray-700 mb-2">接种日期</label>
                <input 
                  v-model="vaccinateForm.actualDate"
                  type="date" 
                  class="input-base text-lg py-3"
                />
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-2">接种医院</label>
                <input 
                  v-model="vaccinateForm.hospital"
                  type="text" 
                  placeholder="请输入接种医院" 
                  class="input-base text-lg py-3"
                />
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-2">疫苗批号</label>
                <input 
                  v-model="vaccinateForm.batchNumber"
                  type="text" 
                  placeholder="请输入疫苗批号" 
                  class="input-base text-lg py-3"
                />
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-2">接种部位</label>
                <input 
                  v-model="vaccinateForm.injectionSite"
                  type="text" 
                  placeholder="如：上臂三角肌" 
                  class="input-base text-lg py-3"
                />
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-2">不良反应</label>
                <input 
                  v-model="vaccinateForm.adverseReaction"
                  type="text" 
                  placeholder="有无不良反应（可选）" 
                  class="input-base text-lg py-3"
                />
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-2">备注</label>
                <textarea 
                  v-model="vaccinateForm.remark"
                  placeholder="添加备注（可选）" 
                  class="input-base min-h-[80px] resize-none text-base"
                  rows="2"
                ></textarea>
              </div>
            </div>

            <div class="flex gap-3 mt-8">
              <button 
                @click="showVaccinateDialog = false"
                class="flex-1 py-4 rounded-xl border-2 border-gray-200 text-gray-600 text-lg font-medium hover:bg-gray-50 transition-colors"
              >
                取消
              </button>
              <button 
                @click="handleMarkVaccinated"
                class="flex-1 bg-gradient-to-r from-green-400 to-green-500 text-white text-lg py-4 rounded-xl font-medium shadow-soft hover:shadow-card transition-all"
              >
                确认接种
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <Teleport to="body">
      <Transition name="fade">
        <div v-if="showDateDialog" class="fixed inset-0 bg-black/50 z-50 flex items-end sm:items-center justify-center" @click.self="showDateDialog = false">
          <div class="bg-white w-full sm:max-w-lg sm:rounded-3xl rounded-t-3xl p-6 animate-slide-up">
            <div class="flex items-center justify-between mb-6">
              <h3 class="text-xl font-bold text-gray-800">修改计划日期</h3>
              <button 
                @click="showDateDialog = false"
                class="w-10 h-10 rounded-full bg-gray-100 flex items-center justify-center hover:bg-gray-200 transition-colors"
              >
                <X :size="20" class="text-gray-500" />
              </button>
            </div>

            <div v-if="currentVaccine" class="mb-6 p-4 bg-blue-50 rounded-xl">
              <p class="text-lg font-bold text-blue-700">{{ currentVaccine.vaccineName }}</p>
              <p class="text-sm text-blue-600">原计划日期：{{ formatDate(currentVaccine.plannedDate) }}</p>
            </div>

            <div class="space-y-5">
              <div>
                <label class="block text-base font-medium text-gray-700 mb-2">新计划日期</label>
                <input 
                  v-model="dateForm.plannedDate"
                  type="date" 
                  class="input-base text-lg py-3"
                />
              </div>
            </div>

            <div class="flex gap-3 mt-8">
              <button 
                @click="showDateDialog = false"
                class="flex-1 py-4 rounded-xl border-2 border-gray-200 text-gray-600 text-lg font-medium hover:bg-gray-50 transition-colors"
              >
                取消
              </button>
              <button 
                @click="handleUpdateDate"
                class="flex-1 bg-gradient-to-r from-blue-400 to-blue-500 text-white text-lg py-4 rounded-xl font-medium shadow-soft hover:shadow-card transition-all"
              >
                确认修改
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <Teleport to="body">
      <Transition name="fade">
        <div v-if="showDetailDialog && currentVaccine" class="fixed inset-0 bg-black/50 z-50 flex items-end sm:items-center justify-center" @click.self="showDetailDialog = false">
          <div class="bg-white w-full sm:max-w-lg sm:rounded-3xl rounded-t-3xl p-6 animate-slide-up max-h-[90vh] overflow-y-auto">
            <div class="flex items-center justify-between mb-6">
              <h3 class="text-xl font-bold text-gray-800">疫苗详情</h3>
              <button 
                @click="showDetailDialog = false"
                class="w-10 h-10 rounded-full bg-gray-100 flex items-center justify-center hover:bg-gray-200 transition-colors"
              >
                <X :size="20" class="text-gray-500" />
              </button>
            </div>

            <div class="space-y-5">
              <div class="p-4 bg-green-50 rounded-xl">
                <p class="text-xl font-bold text-green-700 mb-1">{{ currentVaccine.vaccineName }}</p>
                <p class="text-base text-green-600">第{{ currentVaccine.doseNumber }}剂 / 共{{ currentVaccine.totalDoses }}剂</p>
              </div>

              <div class="space-y-3">
                <div class="flex items-center justify-between py-2 border-b border-gray-100">
                  <span class="text-base text-gray-500">疫苗类型</span>
                  <span class="text-base font-medium" :class="currentVaccine.type === 'free' ? 'text-green-600' : 'text-orange-600'">
                    {{ currentVaccine.type === 'free' ? '免费' : '自费' }}
                  </span>
                </div>
                <div class="flex items-center justify-between py-2 border-b border-gray-100">
                  <span class="text-base text-gray-500">预防疾病</span>
                  <span class="text-base font-medium text-gray-700">{{ currentVaccine.preventDisease }}</span>
                </div>
                <div class="flex items-center justify-between py-2 border-b border-gray-100">
                  <span class="text-base text-gray-500">计划日期</span>
                  <span class="text-base font-medium text-gray-700">{{ formatDate(currentVaccine.plannedDate) }}</span>
                </div>
                <div v-if="currentVaccine.actualDate" class="flex items-center justify-between py-2 border-b border-gray-100">
                  <span class="text-base text-gray-500">实际接种日期</span>
                  <span class="text-base font-medium text-green-600">{{ formatDate(currentVaccine.actualDate) }}</span>
                </div>
                <div v-if="currentVaccine.hospital" class="flex items-center justify-between py-2 border-b border-gray-100">
                  <span class="text-base text-gray-500">接种医院</span>
                  <span class="text-base font-medium text-gray-700">{{ currentVaccine.hospital }}</span>
                </div>
                <div v-if="currentVaccine.batchNumber" class="flex items-center justify-between py-2 border-b border-gray-100">
                  <span class="text-base text-gray-500">疫苗批号</span>
                  <span class="text-base font-medium text-gray-700">{{ currentVaccine.batchNumber }}</span>
                </div>
                <div v-if="currentVaccine.injectionSite" class="flex items-center justify-between py-2 border-b border-gray-100">
                  <span class="text-base text-gray-500">接种部位</span>
                  <span class="text-base font-medium text-gray-700">{{ currentVaccine.injectionSite }}</span>
                </div>
                <div v-if="currentVaccine.adverseReaction" class="flex items-center justify-between py-2 border-b border-gray-100">
                  <span class="text-base text-gray-500">不良反应</span>
                  <span class="text-base font-medium text-orange-600">{{ currentVaccine.adverseReaction }}</span>
                </div>
                <div v-if="currentVaccine.remark" class="flex items-start justify-between py-2">
                  <span class="text-base text-gray-500">备注</span>
                  <span class="text-base font-medium text-gray-700 text-right max-w-[60%]">{{ currentVaccine.remark }}</span>
                </div>
              </div>

              <div v-if="currentVaccine.description" class="p-4 bg-blue-50 rounded-xl">
                <p class="text-base font-medium text-blue-700 mb-2">疫苗介绍</p>
                <p class="text-base text-blue-600">{{ currentVaccine.description }}</p>
              </div>

              <div v-if="currentVaccine.precautions" class="p-4 bg-yellow-50 rounded-xl">
                <p class="text-base font-medium text-yellow-700 mb-2">注意事项</p>
                <p class="text-base text-yellow-600">{{ currentVaccine.precautions }}</p>
              </div>
            </div>

            <button 
              @click="showDetailDialog = false"
              class="w-full mt-6 py-4 rounded-xl bg-gradient-to-r from-green-400 to-green-500 text-white text-lg font-medium shadow-soft hover:shadow-card transition-all"
            >
              关闭
            </button>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
</style>
