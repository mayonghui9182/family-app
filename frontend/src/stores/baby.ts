import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Baby, BabyGrowthRecord, BabyDailyRecord, Milestone } from '@/types'
import { babyApi } from '@/api'
import { calculateAge, generateId, dayjs } from '@/utils'
import { mockBaby, mockGrowthRecords, mockMilestones } from '@/mock'

export const useBabyStore = defineStore('baby', () => {
  const babyInfo = ref<Baby | null>(null)
  const growthRecords = ref<BabyGrowthRecord[]>([])
  const dailyRecords = ref<BabyDailyRecord[]>([])
  const milestones = ref<Milestone[]>([])
  const loading = ref(false)

  const age = computed(() => {
    if (!babyInfo.value?.birthDate) return null
    return calculateAge(babyInfo.value.birthDate)
  })

  const ageText = computed(() => {
    return age.value?.text || ''
  })

  const achievedMilestones = computed(() => milestones.value.filter(m => m.achieved))
  const pendingMilestones = computed(() => milestones.value.filter(m => !m.achieved))

  async function fetchBabyInfo(id?: string | number) {
    loading.value = true
    try {
      const data = await babyApi.getBabyInfo(id)
      babyInfo.value = data
      return data
    } catch (e) {
      console.warn('使用mock宝宝信息数据:', e)
      babyInfo.value = mockBaby
      return babyInfo.value
    } finally {
      loading.value = false
    }
  }

  async function updateBabyInfo(id: string | number, data: Partial<Baby>) {
    loading.value = true
    try {
      const result = await babyApi.updateBabyInfo(id, data)
      babyInfo.value = result
      return result
    } catch (e) {
      console.warn('使用mock更新宝宝信息:', e)
      if (babyInfo.value) {
        babyInfo.value = { ...babyInfo.value, ...data }
      }
      return babyInfo.value
    } finally {
      loading.value = false
    }
  }

  async function fetchGrowthRecords(babyId: string | number, params?: {
    page?: number
    pageSize?: number
  }) {
    loading.value = true
    try {
      const data = await babyApi.getGrowthRecords(babyId, params)
      growthRecords.value = data.list
      return data
    } catch (e) {
      console.warn('使用mock成长记录数据:', e)
      growthRecords.value = mockGrowthRecords
      return {
        list: mockGrowthRecords,
        total: mockGrowthRecords.length,
        page: 1,
        pageSize: 10,
        totalPages: 1,
      }
    } finally {
      loading.value = false
    }
  }

  async function addGrowthRecord(babyId: string | number, data: Partial<BabyGrowthRecord>) {
    loading.value = true
    try {
      const result = await babyApi.addGrowthRecord(babyId, data)
      growthRecords.value.unshift(result)
      return result
    } catch (e) {
      console.warn('使用mock添加成长记录:', e)
      const newRecord: BabyGrowthRecord = {
        id: generateId(),
        babyId,
        date: data.date || dayjs().format('YYYY-MM-DD'),
        height: data.height,
        weight: data.weight,
        headCircumference: data.headCircumference,
        note: data.note,
      }
      growthRecords.value.unshift(newRecord)
      return newRecord
    } finally {
      loading.value = false
    }
  }

  async function deleteGrowthRecord(babyId: string | number, recordId: string | number) {
    loading.value = true
    try {
      await babyApi.deleteGrowthRecord(babyId, recordId)
      growthRecords.value = growthRecords.value.filter(r => r.id !== recordId)
    } catch (e) {
      console.warn('使用mock删除成长记录:', e)
      growthRecords.value = growthRecords.value.filter(r => r.id !== recordId)
    } finally {
      loading.value = false
    }
  }

  async function fetchDailyRecords(babyId: string | number, date?: string) {
    loading.value = true
    try {
      const data = await babyApi.getDailyRecords(babyId, date)
      dailyRecords.value = data
      return data
    } catch (e) {
      console.warn('使用mock日常记录数据:', e)
      dailyRecords.value = [
        {
          id: '1',
          babyId,
          date: date || dayjs().format('YYYY-MM-DD'),
          type: 'feeding',
          time: '08:30',
          description: '母乳',
          amount: 150,
          unit: 'ml',
        },
        {
          id: '2',
          babyId,
          date: date || dayjs().format('YYYY-MM-DD'),
          type: 'sleep',
          time: '09:00',
          description: '上午觉',
          amount: 2,
          unit: '小时',
        },
        {
          id: '3',
          babyId,
          date: date || dayjs().format('YYYY-MM-DD'),
          type: 'feeding',
          time: '12:00',
          description: '辅食',
          amount: 100,
          unit: 'g',
        },
        {
          id: '4',
          babyId,
          date: date || dayjs().format('YYYY-MM-DD'),
          type: 'diaper',
          time: '13:00',
          description: '换尿布',
        },
        {
          id: '5',
          babyId,
          date: date || dayjs().format('YYYY-MM-DD'),
          type: 'sleep',
          time: '14:00',
          description: '下午觉',
          amount: 1.5,
          unit: '小时',
        },
      ]
      return dailyRecords.value
    } finally {
      loading.value = false
    }
  }

  async function addDailyRecord(babyId: string | number, data: Partial<BabyDailyRecord>) {
    loading.value = true
    try {
      const result = await babyApi.addDailyRecord(babyId, data)
      dailyRecords.value.unshift(result)
      return result
    } catch (e) {
      console.warn('使用mock添加日常记录:', e)
      const newRecord: BabyDailyRecord = {
        id: generateId(),
        babyId,
        date: data.date || dayjs().format('YYYY-MM-DD'),
        type: (data.type as any) || 'other',
        time: data.time || dayjs().format('HH:mm'),
        description: data.description || '',
        amount: data.amount,
        unit: data.unit,
        note: data.note,
      }
      dailyRecords.value.unshift(newRecord)
      return newRecord
    } finally {
      loading.value = false
    }
  }

  async function deleteDailyRecord(babyId: string | number, recordId: string | number) {
    loading.value = true
    try {
      await babyApi.deleteDailyRecord(babyId, recordId)
      dailyRecords.value = dailyRecords.value.filter(r => r.id !== recordId)
    } catch (e) {
      console.warn('使用mock删除日常记录:', e)
      dailyRecords.value = dailyRecords.value.filter(r => r.id !== recordId)
    } finally {
      loading.value = false
    }
  }

  async function fetchMilestones(babyId: string | number) {
    loading.value = true
    try {
      const data = await babyApi.getMilestones(babyId)
      milestones.value = data
      return data
    } catch (e) {
      console.warn('使用mock里程碑数据:', e)
      milestones.value = mockMilestones
      return milestones.value
    } finally {
      loading.value = false
    }
  }

  async function updateMilestone(babyId: string | number, milestoneId: string | number, achieved: boolean) {
    try {
      const result = await babyApi.updateMilestone(babyId, milestoneId, achieved)
      const index = milestones.value.findIndex(m => m.id === milestoneId)
      if (index !== -1) {
        milestones.value[index] = result
      }
      return result
    } catch (e) {
      console.warn('使用mock更新里程碑:', e)
      const index = milestones.value.findIndex(m => m.id === milestoneId)
      if (index !== -1) {
        milestones.value[index].achieved = achieved
        if (achieved) {
          milestones.value[index].achievedDate = dayjs().format('YYYY-MM-DD')
        }
      }
      return milestones.value[index]
    }
  }

  return {
    babyInfo,
    growthRecords,
    dailyRecords,
    milestones,
    loading,
    age,
    ageText,
    achievedMilestones,
    pendingMilestones,
    fetchBabyInfo,
    updateBabyInfo,
    fetchGrowthRecords,
    addGrowthRecord,
    deleteGrowthRecord,
    fetchDailyRecords,
    addDailyRecord,
    deleteDailyRecord,
    fetchMilestones,
    updateMilestone,
  }
})
