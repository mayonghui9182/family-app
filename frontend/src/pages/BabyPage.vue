<script setup lang="ts">
import { onMounted, ref, computed, watch, nextTick } from 'vue'
import { Heart, Baby as BabyIcon, Ruler, Scale, Calendar, ChevronRight, Activity, Edit, X, Check, Plus, Trash2, Cake, Droplet, Moon, Sun, Utensils, Gamepad2, Pill, StickyNote, MessageCircle, Users, Hand, Syringe, Image, FolderOpen } from 'lucide-vue-next'
import { useBabyStore } from '@/stores/baby'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dayjs } from '@/utils'
import type { BabyRecordType, VaccineStats } from '@/types'
import { vaccineApi, albumApi } from '@/api'
import { generateMockVaccines, generateMockVaccineStats, mockBaby, generateMockAlbums, generateMockPhotos } from '@/mock'
import { useRouter } from 'vue-router'
import type { Album, Photo } from '@/types'

const babyStore = useBabyStore()

const activeTab = ref<'growth' | 'milestone' | 'vaccine' | 'album' | 'info'>('growth')

const albums = ref<Album[]>([])
const recentPhotos = ref<Photo[]>([])
const albumLoading = ref(false)
const growthSubTab = ref<'heightWeight' | 'feeding' | 'sleep' | 'daily'>('heightWeight')
const milestoneCategory = ref<'大运动' | '精细动作' | '语言' | '社交'>('大运动')

const showAddRecordDialog = ref(false)
const showEditBabyDialog = ref(false)

const recordForm = ref({
  type: 'heightWeight' as 'heightWeight' | BabyRecordType,
  height: '',
  weight: '',
  headCircumference: '',
  amount: '',
  unit: '',
  description: '',
  date: dayjs().format('YYYY-MM-DD'),
  time: dayjs().format('HH:mm'),
  note: '',
})

const babyForm = ref({
  name: '',
  nickname: '',
  gender: 'girl' as 'boy' | 'girl',
  birthDate: '',
  birthTime: '',
  birthWeight: '',
  birthHeight: '',
  bloodType: '',
})

const router = useRouter()
const vaccineStats = ref<VaccineStats | null>(null)
const vaccineLoading = ref(false)

async function fetchVaccineStats() {
  vaccineLoading.value = true
  try {
    const babyId = babyStore.babyInfo?.id || 1
    const stats = await vaccineApi.getVaccineStats(babyId)
    vaccineStats.value = stats
  } catch (e) {
    console.warn('使用mock疫苗统计数据:', e)
    const birthDate = babyStore.babyInfo?.birthDate || mockBaby.birthDate
    const mockVaccines = generateMockVaccines(birthDate)
    vaccineStats.value = generateMockVaccineStats(mockVaccines)
  } finally {
    vaccineLoading.value = false
  }
}

function goToVaccinePage() {
  const babyId = babyStore.babyInfo?.id || 1
  router.push(`/baby/${babyId}/vaccine`)
}

async function fetchAlbums() {
  albumLoading.value = true
  try {
    const babyId = babyStore.babyInfo?.id || 1
    const albumList = await albumApi.getAlbumList(babyId)
    albums.value = albumList
    
    const allPhotos: Photo[] = []
    for (const album of albumList.slice(0, 3)) {
      try {
        const result = await albumApi.getPhotoList(babyId, album.id, 1, 9)
        allPhotos.push(...result.list.slice(0, 3))
      } catch (e) {
        console.warn(e)
      }
    }
    recentPhotos.value = allPhotos.slice(0, 9)
  } catch (e) {
    console.warn('使用mock相册数据:', e)
    const babyId = babyStore.babyInfo?.id || 1
    albums.value = generateMockAlbums(babyId)
    
    const mockPhotos = generateMockPhotos(babyId, 1, 9)
    recentPhotos.value = mockPhotos
  } finally {
    albumLoading.value = false
  }
}

function goToAlbumList() {
  const babyId = babyStore.babyInfo?.id || 1
  router.push(`/baby/${babyId}/albums`)
}

const tabs = [
  { key: 'growth' as const, label: '成长记录', icon: Activity },
  { key: 'milestone' as const, label: '发育里程碑', icon: Heart },
  { key: 'vaccine' as const, label: '疫苗接种', icon: Syringe },
  { key: 'album' as const, label: '宝宝相册', icon: Image },
  { key: 'info' as const, label: '宝宝信息', icon: BabyIcon },
]

const growthSubTabs = [
  { key: 'heightWeight' as const, label: '身高体重', icon: Ruler },
  { key: 'feeding' as const, label: '喂养', icon: Utensils },
  { key: 'sleep' as const, label: '睡眠', icon: Moon },
  { key: 'daily' as const, label: '日常', icon: Sun },
]

const milestoneCategories: Array<'大运动' | '精细动作' | '语言' | '社交'> = ['大运动', '精细动作', '语言', '社交']

const milestoneCategoryIcons: Record<string, any> = {
  '大运动': Activity,
  '精细动作': Hand,
  '语言': MessageCircle,
  '社交': Users,
}

const recordTypeIcons: Record<string, string> = {
  feeding: '🍼',
  sleep: '😴',
  diaper: '💩',
  bath: '🛁',
  play: '🎮',
  medicine: '💊',
  other: '📝',
}

const recordTypeNames: Record<string, string> = {
  feeding: '喂奶',
  sleep: '睡眠',
  diaper: '换尿布',
  bath: '洗澡',
  play: '玩耍',
  medicine: '吃药',
  other: '其他',
}

onMounted(async () => {
  await Promise.all([
    babyStore.fetchBabyInfo(),
    babyStore.fetchGrowthRecords(1),
    babyStore.fetchMilestones(1),
    babyStore.fetchDailyRecords(1),
  ])
  if (babyStore.babyInfo) {
    babyForm.value = {
      name: babyStore.babyInfo.name,
      nickname: babyStore.babyInfo.nickname || '',
      gender: babyStore.babyInfo.gender,
      birthDate: babyStore.babyInfo.birthDate,
      birthTime: babyStore.babyInfo.birthTime || '',
      birthWeight: babyStore.babyInfo.birthWeight?.toString() || '',
      birthHeight: babyStore.babyInfo.birthHeight?.toString() || '',
      bloodType: babyStore.babyInfo.bloodType || '',
    }
  }
  await Promise.all([
    fetchVaccineStats(),
    fetchAlbums(),
  ])
})

const babyInfo = computed(() => babyStore.babyInfo)
const ageText = computed(() => babyStore.ageText)
const growthRecords = computed(() => babyStore.growthRecords)
const milestones = computed(() => babyStore.milestones)
const dailyRecords = computed(() => babyStore.dailyRecords)

const sortedGrowthRecords = computed(() => {
  return [...growthRecords.value].sort((a, b) => 
    new Date(b.date).getTime() - new Date(a.date).getTime()
  )
})

const filteredMilestones = computed(() => {
  return milestones.value.filter(m => m.category === milestoneCategory.value)
})

const latestRecord = computed(() => {
  if (growthRecords.value.length === 0) return null
  return [...growthRecords.value].sort((a, b) => 
    new Date(b.date).getTime() - new Date(a.date).getTime()
  )[0]
})

const prevRecord = computed(() => {
  if (growthRecords.value.length < 2) return null
  const sorted = [...growthRecords.value].sort((a, b) => 
    new Date(b.date).getTime() - new Date(a.date).getTime()
  )
  return sorted[1]
})

const heightChange = computed(() => {
  if (!latestRecord.value || !prevRecord.value) return 0
  return (latestRecord.value.height || 0) - (prevRecord.value.height || 0)
})

const weightChange = computed(() => {
  if (!latestRecord.value || !prevRecord.value) return 0
  return Number(((latestRecord.value.weight || 0) - (prevRecord.value.weight || 0)).toFixed(1))
})

const openAddRecordDialog = () => {
  recordForm.value = {
    type: growthSubTab.value === 'heightWeight' ? 'heightWeight' : 'feeding',
    height: '',
    weight: '',
    headCircumference: '',
    amount: '',
    unit: '',
    description: '',
    date: dayjs().format('YYYY-MM-DD'),
    time: dayjs().format('HH:mm'),
    note: '',
  }
  showAddRecordDialog.value = true
}

const handleAddRecord = async () => {
  try {
    const babyId = babyStore.babyInfo?.id || 1
    
    if (recordForm.value.type === 'heightWeight') {
      await babyStore.addGrowthRecord(babyId, {
        date: recordForm.value.date,
        height: recordForm.value.height ? Number(recordForm.value.height) : undefined,
        weight: recordForm.value.weight ? Number(recordForm.value.weight) : undefined,
        headCircumference: recordForm.value.headCircumference ? Number(recordForm.value.headCircumference) : undefined,
        note: recordForm.value.note,
      })
    } else {
      await babyStore.addDailyRecord(babyId, {
        type: recordForm.value.type as BabyRecordType,
        date: recordForm.value.date,
        time: recordForm.value.time,
        description: recordForm.value.description,
        amount: recordForm.value.amount ? Number(recordForm.value.amount) : undefined,
        unit: recordForm.value.unit || undefined,
        note: recordForm.value.note,
      })
    }
    
    ElMessage.success('添加成功')
    showAddRecordDialog.value = false
  } catch (e) {
    ElMessage.error('添加失败')
    console.error(e)
  }
}

const handleToggleMilestone = async (milestoneId: string | number, achieved: boolean) => {
  try {
    const babyId = babyStore.babyInfo?.id || 1
    await babyStore.updateMilestone(babyId, milestoneId, achieved)
    ElMessage.success(achieved ? '已标记为达成' : '已取消达成')
  } catch (e) {
    console.error(e)
  }
}

const openEditBabyDialog = () => {
  if (babyStore.babyInfo) {
    babyForm.value = {
      name: babyStore.babyInfo.name,
      nickname: babyStore.babyInfo.nickname || '',
      gender: babyStore.babyInfo.gender,
      birthDate: babyStore.babyInfo.birthDate,
      birthTime: babyStore.babyInfo.birthTime || '',
      birthWeight: babyStore.babyInfo.birthWeight?.toString() || '',
      birthHeight: babyStore.babyInfo.birthHeight?.toString() || '',
      bloodType: babyStore.babyInfo.bloodType || '',
    }
  }
  showEditBabyDialog.value = true
}

const handleUpdateBaby = async () => {
  try {
    const babyId = babyStore.babyInfo?.id || 1
    await babyStore.updateBabyInfo(babyId, {
      name: babyForm.value.name,
      nickname: babyForm.value.nickname,
      gender: babyForm.value.gender,
      birthDate: babyForm.value.birthDate,
      birthTime: babyForm.value.birthTime || undefined,
      birthWeight: babyForm.value.birthWeight ? Number(babyForm.value.birthWeight) : undefined,
      birthHeight: babyForm.value.birthHeight ? Number(babyForm.value.birthHeight) : undefined,
      bloodType: babyForm.value.bloodType || undefined,
    })
    ElMessage.success('保存成功')
    showEditBabyDialog.value = false
  } catch (e) {
    ElMessage.error('保存失败')
    console.error(e)
  }
}

const formatDate = (date: string) => {
  return dayjs(date).format('M月D日')
}

const handleDeleteRecord = async (recordId: string | number, type: 'growth' | 'daily') => {
  try {
    await ElMessageBox.confirm('确定要删除这条记录吗？', '删除记录', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
    const babyId = babyStore.babyInfo?.id || 1
    if (type === 'growth') {
      await babyStore.deleteGrowthRecord(babyId, recordId)
    } else {
      await babyStore.deleteDailyRecord(babyId, recordId)
    }
    ElMessage.success('删除成功')
  } catch {
  }
}
</script>

<template>
  <div class="page-container">
    <div class="safe-area-top bg-gradient-to-b from-pink-100 to-transparent pb-4">
      <div class="px-5 pt-4">
        <div class="flex items-center justify-between mb-6">
          <h1 class="text-2xl font-bold text-gray-800">宝宝成长</h1>
          <button 
            @click="openEditBabyDialog"
            class="w-12 h-12 rounded-full bg-white flex items-center justify-center shadow-soft hover:shadow-card transition-shadow"
          >
            <Edit :size="22" class="text-pink-500" />
          </button>
        </div>

        <div v-if="babyInfo" class="card-base bg-gradient-to-r from-pink-400 to-pink-500 text-white animate-slide-up">
          <div class="flex items-center gap-4">
            <div class="w-20 h-20 rounded-2xl bg-white/20 backdrop-blur-sm flex items-center justify-center text-5xl">
              👶
            </div>
            <div class="flex-1">
              <h2 class="text-xl font-bold mb-1">{{ babyInfo.name }}</h2>
              <p class="text-white/80 text-base mb-2">
                {{ babyInfo.gender === 'girl' ? '女宝宝' : '男宝宝' }}
                <span v-if="babyInfo.nickname"> · {{ babyInfo.nickname }}</span>
              </p>
              <div class="flex items-center gap-1 text-white/90">
                <Calendar :size="16" />
                <span class="text-base font-medium">{{ ageText }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="px-5">
      <div class="flex gap-2 bg-white p-1 rounded-xl shadow-soft mb-6">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          @click="activeTab = tab.key"
          :class="[
            'flex-1 py-3 rounded-lg text-base font-medium transition-all flex items-center justify-center gap-2',
            activeTab === tab.key
              ? 'bg-gradient-to-r from-pink-400 to-pink-500 text-white shadow-soft'
              : 'text-gray-500 hover:text-gray-700'
          ]"
        >
          <component :is="tab.icon" :size="18" />
          {{ tab.label }}
        </button>
      </div>

      <div v-show="activeTab === 'growth'" class="space-y-6 pb-6 animate-fade-in">
        <div class="flex gap-2 bg-white p-1.5 rounded-xl shadow-soft">
          <button
            v-for="tab in growthSubTabs"
            :key="tab.key"
            @click="growthSubTab = tab.key"
            :class="[
              'flex-1 py-2.5 rounded-lg text-sm font-medium transition-all flex items-center justify-center gap-1.5',
              growthSubTab === tab.key
                ? 'bg-pink-100 text-pink-600'
                : 'text-gray-500 hover:text-gray-700'
            ]"
          >
            <component :is="tab.icon" :size="16" />
            {{ tab.label }}
          </button>
        </div>

        <button 
          @click="openAddRecordDialog"
          class="w-full btn-primary text-lg py-4 flex items-center justify-center gap-2"
        >
          <Plus :size="22" />
          添加记录
        </button>

        <div v-show="growthSubTab === 'heightWeight'" class="space-y-4">
          <div class="grid grid-cols-2 gap-3">
            <div class="card-base">
              <div class="flex items-center gap-2 mb-2">
                <div class="w-10 h-10 rounded-lg bg-blue-50 flex items-center justify-center">
                  <Ruler :size="20" class="text-blue-500" />
                </div>
                <span class="text-base text-gray-500">身高</span>
              </div>
              <p class="text-2xl font-bold text-gray-800">
                {{ latestRecord?.height || '--' }}
                <span class="text-sm font-normal text-gray-500">cm</span>
              </p>
              <p v-if="heightChange" class="text-sm text-secondary-500 mt-1">
                ↑ 比上次增长 {{ heightChange }}cm
              </p>
            </div>
            <div class="card-base">
              <div class="flex items-center gap-2 mb-2">
                <div class="w-10 h-10 rounded-lg bg-green-50 flex items-center justify-center">
                  <Scale :size="20" class="text-green-500" />
                </div>
                <span class="text-base text-gray-500">体重</span>
              </div>
              <p class="text-2xl font-bold text-gray-800">
                {{ latestRecord?.weight || '--' }}
                <span class="text-sm font-normal text-gray-500">kg</span>
              </p>
              <p v-if="weightChange" class="text-sm text-secondary-500 mt-1">
                ↑ 比上次增长 {{ weightChange }}kg
              </p>
            </div>
          </div>

          <div>
            <h3 class="text-lg font-bold text-gray-800 mb-3">记录列表</h3>
            <div class="space-y-3">
              <div 
                v-for="(record, index) in sortedGrowthRecords" 
                :key="record.id"
                class="card-base animate-slide-up"
                :style="{ animationDelay: `${index * 30}ms` }"
              >
                <div class="flex items-center justify-between">
                  <div class="flex items-center gap-3">
                    <div class="w-12 h-12 rounded-xl bg-orange-50 flex items-center justify-center">
                      <Activity :size="24" class="text-orange-500" />
                    </div>
                    <div>
                      <p class="text-base font-medium text-gray-800">{{ formatDate(record.date) }}</p>
                      <p class="text-sm text-gray-500">
                        <span v-if="record.height">身高 {{ record.height }}cm</span>
                        <span v-if="record.weight"> · 体重 {{ record.weight }}kg</span>
                      </p>
                    </div>
                  </div>
                  <button 
                    @click="handleDeleteRecord(record.id, 'growth')"
                    class="w-10 h-10 rounded-full bg-red-50 flex items-center justify-center hover:bg-red-100 transition-colors"
                  >
                    <Trash2 :size="18" class="text-red-400" />
                  </button>
                </div>
                <p v-if="record.note" class="text-sm text-gray-400 mt-3">{{ record.note }}</p>
              </div>
            </div>
          </div>
        </div>

        <div v-show="growthSubTab === 'feeding' || growthSubTab === 'sleep' || growthSubTab === 'daily'" class="space-y-4">
          <div>
            <h3 class="text-lg font-bold text-gray-800 mb-3">今日记录</h3>
            <div class="space-y-3">
              <div 
                v-for="(record, index) in dailyRecords.filter(r => {
                  if (growthSubTab === 'feeding') return r.type === 'feeding'
                  if (growthSubTab === 'sleep') return r.type === 'sleep'
                  return true
                })" 
                :key="record.id"
                class="card-base animate-slide-up"
                :style="{ animationDelay: `${index * 30}ms` }"
              >
                <div class="flex items-center justify-between">
                  <div class="flex items-center gap-3">
                    <div class="w-12 h-12 rounded-xl bg-orange-50 flex items-center justify-center text-2xl">
                      {{ recordTypeIcons[record.type] || '📝' }}
                    </div>
                    <div>
                      <p class="text-base font-medium text-gray-800">
                        {{ recordTypeNames[record.type] || record.description }}
                      </p>
                      <p class="text-sm text-gray-500">
                        {{ record.description }}
                        <span v-if="record.amount"> · {{ record.amount }}{{ record.unit || '' }}</span>
                      </p>
                    </div>
                  </div>
                  <div class="flex items-center gap-2">
                    <span class="text-sm text-gray-400">{{ record.time }}</span>
                    <button 
                      @click="handleDeleteRecord(record.id, 'daily')"
                      class="w-10 h-10 rounded-full bg-red-50 flex items-center justify-center hover:bg-red-100 transition-colors"
                    >
                      <Trash2 :size="18" class="text-red-400" />
                    </button>
                  </div>
                </div>
                <p v-if="record.note" class="text-sm text-gray-400 mt-3">{{ record.note }}</p>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-show="activeTab === 'milestone'" class="space-y-6 pb-6 animate-fade-in">
        <div class="flex gap-2 bg-white p-1.5 rounded-xl shadow-soft overflow-x-auto scrollbar-hide">
          <button
            v-for="cat in milestoneCategories"
            :key="cat"
            @click="milestoneCategory = cat"
            :class="[
              'flex-shrink-0 px-4 py-2.5 rounded-lg text-base font-medium transition-all flex items-center gap-2',
              milestoneCategory === cat
                ? 'bg-pink-100 text-pink-600'
                : 'text-gray-500 hover:text-gray-700'
            ]"
          >
            <component :is="milestoneCategoryIcons[cat]" :size="18" />
            {{ cat }}
          </button>
        </div>

        <div class="flex items-center justify-between">
          <h3 class="text-lg font-bold text-gray-800">
            {{ milestoneCategory }}
          </h3>
          <span class="text-base text-gray-400">
            已达成 {{ milestones.filter(m => m.category === milestoneCategory && m.achieved).length }} / {{ milestones.filter(m => m.category === milestoneCategory).length }}
          </span>
        </div>

        <div class="space-y-4">
          <div 
            v-for="(milestone, index) in filteredMilestones" 
            :key="milestone.id"
            class="card-base animate-slide-up"
            :style="{ animationDelay: `${index * 50}ms` }"
            :class="[
              milestone.achieved ? 'ring-2 ring-green-300 bg-green-50/30' : ''
            ]"
          >
            <div class="flex items-start gap-4">
              <div 
                :class="[
                  'w-14 h-14 rounded-2xl flex items-center justify-center flex-shrink-0',
                  milestone.achieved ? 'bg-green-100' : 'bg-gray-100'
                ]"
              >
                <Check v-if="milestone.achieved" :size="28" class="text-green-500" />
                <Activity v-else :size="28" class="text-gray-400" />
              </div>
              <div class="flex-1">
                <div class="flex items-start justify-between mb-2">
                  <div>
                    <h4 
                      :class="[
                        'text-lg font-semibold',
                        milestone.achieved ? 'text-green-600' : 'text-gray-800'
                      ]"
                    >
                      {{ milestone.title }}
                    </h4>
                    <p class="text-sm text-gray-400">预计{{ milestone.expectedAge }}</p>
                  </div>
                  <span 
                    :class="[
                      'text-sm px-3 py-1 rounded-full font-medium',
                      milestone.achieved 
                        ? 'bg-green-100 text-green-600' 
                        : 'bg-orange-100 text-orange-500'
                    ]"
                  >
                    {{ milestone.achieved ? '已达成' : '未达成' }}
                  </span>
                </div>
                <p class="text-base text-gray-500 mb-4">{{ milestone.description }}</p>
                <button 
                  @click="handleToggleMilestone(milestone.id, !milestone.achieved)"
                  :class="[
                    'w-full py-3.5 rounded-xl text-base font-medium transition-all flex items-center justify-center gap-2',
                    milestone.achieved 
                      ? 'bg-gray-100 text-gray-600 hover:bg-gray-200' 
                      : 'bg-gradient-to-r from-pink-400 to-pink-500 text-white hover:shadow-card'
                  ]"
                >
                  <Check v-if="milestone.achieved" :size="20" />
                  <span>{{ milestone.achieved ? '取消达成' : '标记达成' }}</span>
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-show="activeTab === 'vaccine'" class="space-y-6 pb-6 animate-fade-in">
        <div v-if="vaccineStats" class="card-base bg-gradient-to-r from-green-400 to-green-500 text-white">
          <div class="flex items-center justify-between mb-4">
            <div class="flex items-center gap-3">
              <div class="w-12 h-12 rounded-xl bg-white/20 flex items-center justify-center">
                <Syringe :size="24" />
              </div>
              <div>
                <h3 class="text-lg font-bold">疫苗接种</h3>
                <p class="text-white/80 text-sm">保护宝宝健康成长</p>
              </div>
            </div>
            <ChevronRight :size="24" class="text-white/80" />
          </div>
          
          <div class="mb-4">
            <div class="flex items-center justify-between mb-2">
              <span class="text-white/90">接种进度</span>
              <span class="font-bold">{{ vaccineStats.progress }}%</span>
            </div>
            <div class="w-full h-3 bg-white/20 rounded-full overflow-hidden">
              <div 
                class="h-full bg-white rounded-full transition-all duration-500"
                :style="{ width: `${vaccineStats.progress}%` }"
              ></div>
            </div>
          </div>

          <div class="grid grid-cols-3 gap-3">
            <div class="text-center">
              <p class="text-2xl font-bold">{{ vaccineStats.completed }}</p>
              <p class="text-white/80 text-sm">已接种</p>
            </div>
            <div class="text-center">
              <p class="text-2xl font-bold">{{ vaccineStats.pending }}</p>
              <p class="text-white/80 text-sm">待接种</p>
            </div>
            <div class="text-center">
              <p class="text-2xl font-bold" :class="vaccineStats.overdue > 0 ? 'text-yellow-200' : ''">
                {{ vaccineStats.overdue }}
              </p>
              <p class="text-white/80 text-sm">逾期</p>
            </div>
          </div>
        </div>

        <div v-if="vaccineStats?.nextVaccineName" class="card-base">
          <div class="flex items-center gap-3 mb-3">
            <div class="w-12 h-12 rounded-xl bg-blue-50 flex items-center justify-center">
              <Calendar :size="24" class="text-blue-500" />
            </div>
            <div class="flex-1">
              <p class="text-base text-gray-500">下一次接种</p>
              <p class="text-lg font-bold text-gray-800">{{ vaccineStats.nextVaccineName }}</p>
            </div>
          </div>
          <p class="text-base text-blue-600 font-medium">
            {{ vaccineStats.nextVaccineDate ? dayjs(vaccineStats.nextVaccineDate).format('YYYY年M月D日') : '--' }}
          </p>
        </div>

        <button 
          @click="goToVaccinePage"
          class="w-full btn-secondary text-lg py-4 flex items-center justify-center gap-2"
        >
          <Syringe :size="22" />
          查看疫苗详情
          <ChevronRight :size="20" />
        </button>
      </div>

      <div v-show="activeTab === 'album'" class="space-y-6 pb-6 animate-fade-in">
        <div class="card-base bg-gradient-to-r from-pink-400 to-pink-500 text-white">
          <div class="flex items-center justify-between mb-4">
            <div class="flex items-center gap-3">
              <div class="w-12 h-12 rounded-xl bg-white/20 flex items-center justify-center">
                <Image :size="24" />
              </div>
              <div>
                <h3 class="text-lg font-bold">宝宝相册</h3>
                <p class="text-white/80 text-sm">记录成长的每一个瞬间</p>
              </div>
            </div>
            <ChevronRight :size="24" class="text-white/80" />
          </div>
          
          <div class="grid grid-cols-2 gap-3 mb-4">
            <div class="bg-white/20 rounded-xl p-3 text-center">
              <p class="text-3xl font-bold">{{ albums.length }}</p>
              <p class="text-white/80 text-sm">相册数量</p>
            </div>
            <div class="bg-white/20 rounded-xl p-3 text-center">
              <p class="text-3xl font-bold">{{ albums.reduce((sum, a) => sum + a.photoCount, 0) }}</p>
              <p class="text-white/80 text-sm">照片总数</p>
            </div>
          </div>
        </div>

        <div class="card-base">
          <div class="flex items-center justify-between mb-4">
            <h3 class="text-lg font-bold text-gray-800">最近照片</h3>
          </div>
          
          <div v-if="albumLoading" class="py-8 text-center">
            <p class="text-base text-gray-400">加载中...</p>
          </div>

          <div v-else-if="recentPhotos.length === 0" class="py-8 text-center">
            <div class="w-16 h-16 mx-auto mb-3 rounded-full bg-pink-50 flex items-center justify-center">
              <Image :size="28" class="text-pink-300" />
            </div>
            <p class="text-base text-gray-400">还没有照片</p>
          </div>

          <div v-else class="grid grid-cols-3 gap-1.5">
            <div 
              v-for="(photo, index) in recentPhotos.slice(0, 9)" 
              :key="photo.id"
              class="aspect-square bg-pink-50 rounded-lg overflow-hidden"
            >
              <el-image
                :src="photo.url"
                fit="cover"
                class="w-full h-full"
                :preview-src-list="recentPhotos.map(p => p.url)"
                :initial-index="index"
              />
            </div>
          </div>
        </div>

        <button 
          @click="goToAlbumList"
          class="w-full bg-gradient-to-r from-pink-400 to-pink-500 text-white text-lg py-4 rounded-xl font-medium shadow-soft hover:shadow-card transition-all flex items-center justify-center gap-2"
        >
          <FolderOpen :size="22" />
          查看全部相册
          <ChevronRight :size="20" />
        </button>
      </div>

      <div v-show="activeTab === 'info'" class="space-y-6 pb-6 animate-fade-in">
        <div v-if="babyInfo" class="card-base">
          <div class="flex items-center justify-between mb-6">
            <h3 class="text-lg font-bold text-gray-800">基本信息</h3>
            <button 
              @click="openEditBabyDialog"
              class="flex items-center gap-2 text-pink-500 text-base font-medium hover:text-pink-600"
            >
              <Edit :size="18" />
              编辑
            </button>
          </div>
          
          <div class="space-y-4">
            <div class="flex items-center justify-between py-3 border-b border-gray-100">
              <span class="text-base text-gray-500">姓名</span>
              <span class="text-base font-medium text-gray-800">{{ babyInfo.name }}</span>
            </div>
            <div class="flex items-center justify-between py-3 border-b border-gray-100">
              <span class="text-base text-gray-500">小名</span>
              <span class="text-base font-medium text-gray-800">{{ babyInfo.nickname || '--' }}</span>
            </div>
            <div class="flex items-center justify-between py-3 border-b border-gray-100">
              <span class="text-base text-gray-500">性别</span>
              <span class="text-base font-medium text-gray-800">{{ babyInfo.gender === 'girl' ? '女宝宝' : '男宝宝' }}</span>
            </div>
            <div class="flex items-center justify-between py-3 border-b border-gray-100">
              <span class="text-base text-gray-500">生日</span>
              <span class="text-base font-medium text-gray-800">{{ formatDate(babyInfo.birthDate) }}</span>
            </div>
            <div class="flex items-center justify-between py-3 border-b border-gray-100">
              <span class="text-base text-gray-500">出生时间</span>
              <span class="text-base font-medium text-gray-800">{{ babyInfo.birthTime || '--' }}</span>
            </div>
            <div class="flex items-center justify-between py-3 border-b border-gray-100">
              <span class="text-base text-gray-500">出生体重</span>
              <span class="text-base font-medium text-gray-800">{{ babyInfo.birthWeight ? babyInfo.birthWeight + ' kg' : '--' }}</span>
            </div>
            <div class="flex items-center justify-between py-3 border-b border-gray-100">
              <span class="text-base text-gray-500">出生身高</span>
              <span class="text-base font-medium text-gray-800">{{ babyInfo.birthHeight ? babyInfo.birthHeight + ' cm' : '--' }}</span>
            </div>
            <div class="flex items-center justify-between py-3">
              <span class="text-base text-gray-500">血型</span>
              <span class="text-base font-medium text-gray-800">{{ babyInfo.bloodType || '--' }}</span>
            </div>
          </div>
        </div>

        <div class="card-base">
          <div class="flex items-center gap-3 mb-4">
            <div class="w-12 h-12 rounded-xl bg-pink-100 flex items-center justify-center">
              <Cake :size="24" class="text-pink-500" />
            </div>
            <div>
              <p class="text-base font-medium text-gray-800">成长统计</p>
              <p class="text-sm text-gray-500">宝宝成长记录</p>
            </div>
          </div>
          <div class="grid grid-cols-2 gap-4">
            <div class="bg-pink-50 rounded-xl p-4 text-center">
              <p class="text-3xl font-bold text-pink-500">{{ growthRecords.length }}</p>
              <p class="text-base text-gray-500 mt-1">成长记录</p>
            </div>
            <div class="bg-green-50 rounded-xl p-4 text-center">
              <p class="text-3xl font-bold text-green-500">{{ milestones.filter(m => m.achieved).length }}</p>
              <p class="text-base text-gray-500 mt-1">达成里程碑</p>
            </div>
          </div>
        </div>
      </div>
    </div>

    <Teleport to="body">
      <Transition name="fade">
        <div v-if="showAddRecordDialog" class="fixed inset-0 bg-black/50 z-50 flex items-end sm:items-center justify-center" @click.self="showAddRecordDialog = false">
          <div class="bg-white w-full sm:max-w-lg sm:rounded-3xl rounded-t-3xl p-6 animate-slide-up max-h-[90vh] overflow-y-auto">
            <div class="flex items-center justify-between mb-6">
              <h3 class="text-xl font-bold text-gray-800">添加记录</h3>
              <button 
                @click="showAddRecordDialog = false"
                class="w-10 h-10 rounded-full bg-gray-100 flex items-center justify-center hover:bg-gray-200 transition-colors"
              >
                <X :size="20" class="text-gray-500" />
              </button>
            </div>

            <div class="space-y-5">
              <div v-if="growthSubTab === 'heightWeight'">
                <label class="block text-base font-medium text-gray-700 mb-3">记录类型</label>
                <div class="grid grid-cols-3 gap-3">
                  <button 
                    @click="recordForm.type = 'heightWeight'"
                    :class="[
                      'py-4 rounded-xl text-base font-medium transition-all flex flex-col items-center gap-2',
                      recordForm.type === 'heightWeight'
                        ? 'bg-orange-100 text-orange-600 ring-2 ring-orange-200'
                        : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                    ]"
                  >
                    <Activity :size="24" />
                    <span>身高体重</span>
                  </button>
                </div>
              </div>

              <div v-show="recordForm.type === 'heightWeight'" class="space-y-4">
                <div class="grid grid-cols-2 gap-4">
                  <div>
                    <label class="block text-base font-medium text-gray-700 mb-2">身高 (cm)</label>
                    <input 
                      v-model="recordForm.height"
                      type="number" 
                      placeholder="身高" 
                      class="input-base text-lg py-3"
                    />
                  </div>
                  <div>
                    <label class="block text-base font-medium text-gray-700 mb-2">体重 (kg)</label>
                    <input 
                      v-model="recordForm.weight"
                      type="number" 
                      placeholder="体重" 
                      class="input-base text-lg py-3"
                    />
                  </div>
                </div>
                <div>
                  <label class="block text-base font-medium text-gray-700 mb-2">头围 (cm) <span class="text-gray-400 text-sm">可选</span></label>
                  <input 
                    v-model="recordForm.headCircumference"
                    type="number" 
                    placeholder="头围" 
                    class="input-base text-lg py-3"
                  />
                </div>
              </div>

              <div v-show="growthSubTab !== 'heightWeight'">
                <label class="block text-base font-medium text-gray-700 mb-3">记录类型</label>
                <div class="grid grid-cols-4 gap-2">
                  <button 
                    v-for="(name, key) in recordTypeNames" 
                    :key="key"
                    @click="recordForm.type = key as BabyRecordType"
                    :class="[
                      'py-3 rounded-xl text-sm font-medium transition-all flex flex-col items-center gap-1',
                      recordForm.type === key
                        ? 'bg-pink-100 text-pink-600 ring-2 ring-pink-200'
                        : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                    ]"
                  >
                    <span class="text-2xl">{{ recordTypeIcons[key] }}</span>
                    <span>{{ name }}</span>
                  </button>
                </div>
              </div>

              <div v-show="growthSubTab !== 'heightWeight' && recordForm.type !== 'heightWeight'" class="space-y-4">
                <div class="grid grid-cols-2 gap-4">
                  <div>
                    <label class="block text-base font-medium text-gray-700 mb-2">数量</label>
                    <input 
                      v-model="recordForm.amount"
                      type="number" 
                      placeholder="数量" 
                      class="input-base text-lg py-3"
                    />
                  </div>
                  <div>
                    <label class="block text-base font-medium text-gray-700 mb-2">单位</label>
                    <input 
                      v-model="recordForm.unit"
                      type="text" 
                      placeholder="ml/g/小时" 
                      class="input-base text-lg py-3"
                    />
                  </div>
                </div>
                <div>
                  <label class="block text-base font-medium text-gray-700 mb-2">描述</label>
                  <input 
                    v-model="recordForm.description"
                    type="text" 
                    placeholder="描述" 
                    class="input-base text-lg py-3"
                  />
                </div>
              </div>

              <div class="grid grid-cols-2 gap-4">
                <div>
                  <label class="block text-base font-medium text-gray-700 mb-2">日期</label>
                  <input 
                    v-model="recordForm.date"
                    type="date" 
                    class="input-base text-base py-3"
                  />
                </div>
                <div v-show="growthSubTab !== 'heightWeight' || recordForm.type !== 'heightWeight'">
                  <label class="block text-base font-medium text-gray-700 mb-2">时间</label>
                  <input 
                    v-model="recordForm.time"
                    type="time" 
                    class="input-base text-base py-3"
                  />
                </div>
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-2">备注</label>
                <textarea 
                  v-model="recordForm.note"
                  placeholder="添加备注（可选）" 
                  class="input-base min-h-[80px] resize-none text-base"
                  rows="2"
                ></textarea>
              </div>
            </div>

            <div class="flex gap-3 mt-8">
              <button 
                @click="showAddRecordDialog = false"
                class="flex-1 py-4 rounded-xl border-2 border-gray-200 text-gray-600 text-lg font-medium hover:bg-gray-50 transition-colors"
              >
                取消
              </button>
              <button 
                @click="handleAddRecord"
                class="flex-1 bg-gradient-to-r from-pink-400 to-pink-500 text-white text-lg py-4 rounded-xl font-medium shadow-soft hover:shadow-card transition-all"
              >
                添加
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <Teleport to="body">
      <Transition name="fade">
        <div v-if="showEditBabyDialog" class="fixed inset-0 bg-black/50 z-50 flex items-end sm:items-center justify-center" @click.self="showEditBabyDialog = false">
          <div class="bg-white w-full sm:max-w-lg sm:rounded-3xl rounded-t-3xl p-6 animate-slide-up max-h-[90vh] overflow-y-auto">
            <div class="flex items-center justify-between mb-6">
              <h3 class="text-xl font-bold text-gray-800">编辑宝宝信息</h3>
              <button 
                @click="showEditBabyDialog = false"
                class="w-10 h-10 rounded-full bg-gray-100 flex items-center justify-center hover:bg-gray-200 transition-colors"
              >
                <X :size="20" class="text-gray-500" />
              </button>
            </div>

            <div class="space-y-5">
              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">姓名</label>
                <input 
                  v-model="babyForm.name"
                  type="text" 
                  placeholder="宝宝姓名" 
                  class="input-base text-lg py-4"
                />
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">小名</label>
                <input 
                  v-model="babyForm.nickname"
                  type="text" 
                  placeholder="小名（可选）" 
                  class="input-base text-lg py-4"
                />
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">性别</label>
                <div class="grid grid-cols-2 gap-3">
                  <button 
                    @click="babyForm.gender = 'boy'"
                    :class="[
                      'py-4 rounded-xl text-lg font-medium transition-all',
                      babyForm.gender === 'boy'
                        ? 'bg-blue-100 text-blue-600 ring-2 ring-blue-200'
                        : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                    ]"
                  >
                    👦 男宝宝
                  </button>
                  <button 
                    @click="babyForm.gender = 'girl'"
                    :class="[
                      'py-4 rounded-xl text-lg font-medium transition-all',
                      babyForm.gender === 'girl'
                        ? 'bg-pink-100 text-pink-600 ring-2 ring-pink-200'
                        : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                    ]"
                  >
                    👧 女宝宝
                  </button>
                </div>
              </div>

              <div class="grid grid-cols-2 gap-4">
                <div>
                  <label class="block text-base font-medium text-gray-700 mb-3">生日</label>
                  <input 
                    v-model="babyForm.birthDate"
                    type="date" 
                    class="input-base text-base py-3"
                  />
                </div>
                <div>
                  <label class="block text-base font-medium text-gray-700 mb-3">出生时间</label>
                  <input 
                    v-model="babyForm.birthTime"
                    type="time" 
                    class="input-base text-base py-3"
                  />
                </div>
              </div>

              <div class="grid grid-cols-2 gap-4">
                <div>
                  <label class="block text-base font-medium text-gray-700 mb-3">出生体重 (kg)</label>
                  <input 
                    v-model="babyForm.birthWeight"
                    type="number" 
                    step="0.01"
                    placeholder="体重" 
                    class="input-base text-base py-3"
                  />
                </div>
                <div>
                  <label class="block text-base font-medium text-gray-700 mb-3">出生身高 (cm)</label>
                  <input 
                    v-model="babyForm.birthHeight"
                    type="number" 
                    placeholder="身高" 
                    class="input-base text-base py-3"
                  />
                </div>
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">血型</label>
                <div class="grid grid-cols-4 gap-2">
                  <button 
                    v-for="type in ['A', 'B', 'AB', 'O']" 
                    :key="type"
                    @click="babyForm.bloodType = type"
                    :class="[
                      'py-3 rounded-xl text-base font-medium transition-all',
                      babyForm.bloodType === type
                        ? 'bg-pink-100 text-pink-600 ring-2 ring-pink-200'
                        : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                    ]"
                  >
                    {{ type }}
                  </button>
                </div>
              </div>
            </div>

            <div class="flex gap-3 mt-8">
              <button 
                @click="showEditBabyDialog = false"
                class="flex-1 py-4 rounded-xl border-2 border-gray-200 text-gray-600 text-lg font-medium hover:bg-gray-50 transition-colors"
              >
                取消
              </button>
              <button 
                @click="handleUpdateBaby"
                class="flex-1 bg-gradient-to-r from-pink-400 to-pink-500 text-white text-lg py-4 rounded-xl font-medium shadow-soft hover:shadow-card transition-all"
              >
                保存
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
