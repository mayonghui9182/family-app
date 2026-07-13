import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Reminder, PageResult } from '@/types'
import { reminderApi } from '@/api'
import { generateId, dayjs } from '@/utils'
import { mockReminders } from '@/mock'

export const useReminderStore = defineStore('reminder', () => {
  const reminders = ref<Reminder[]>([])
  const todayReminders = ref<Reminder[]>([])
  const currentReminder = ref<Reminder | null>(null)
  const loading = ref(false)
  const total = ref(0)
  const page = ref(1)
  const pageSize = ref(10)

  const enabledReminders = computed(() => reminders.value.filter(r => r.enabled))
  const disabledReminders = computed(() => reminders.value.filter(r => !r.enabled))

  async function fetchReminders(params?: {
    page?: number
    pageSize?: number
    type?: string
    enabled?: boolean
  }) {
    loading.value = true
    try {
      const data = await reminderApi.getReminderList(params)
      reminders.value = data.list
      total.value = data.total
      page.value = data.page
      pageSize.value = data.pageSize
      return data
    } catch (e) {
      console.warn('使用mock提醒数据:', e)
      let list = [...mockReminders]
      if (params?.type && params.type !== 'all') {
        list = list.filter(item => item.type === params.type)
      }
      if (params?.enabled !== undefined) {
        list = list.filter(item => item.enabled === params.enabled)
      }
      const pageNum = params?.page || 1
      const size = params?.pageSize || 10
      const start = (pageNum - 1) * size
      const end = start + size
      reminders.value = list.slice(start, end)
      total.value = list.length
      page.value = pageNum
      pageSize.value = size
      return {
        list: reminders.value,
        total: list.length,
        page: pageNum,
        pageSize: size,
        totalPages: Math.ceil(list.length / size),
      } as PageResult<Reminder>
    } finally {
      loading.value = false
    }
  }

  async function fetchTodayReminders() {
    loading.value = true
    try {
      const data = await reminderApi.getTodayReminders()
      todayReminders.value = data
      return data
    } catch (e) {
      console.warn('使用mock今日提醒数据:', e)
      todayReminders.value = mockReminders.filter(r => r.enabled).slice(0, 3)
      return todayReminders.value
    } finally {
      loading.value = false
    }
  }

  async function fetchReminderDetail(id: string | number) {
    loading.value = true
    try {
      const data = await reminderApi.getReminderDetail(id)
      currentReminder.value = data
      return data
    } catch (e) {
      console.warn('使用mock提醒详情数据:', e)
      const found = mockReminders.find(item => String(item.id) === String(id))
      currentReminder.value = found || null
      return currentReminder.value
    } finally {
      loading.value = false
    }
  }

  async function createReminder(data: Partial<Reminder>) {
    loading.value = true
    try {
      const result = await reminderApi.createReminder(data)
      reminders.value.unshift(result)
      return result
    } catch (e) {
      console.warn('使用mock创建提醒:', e)
      const newReminder: Reminder = {
        id: generateId(),
        title: data.title || '新提醒',
        date: data.date || new Date().toISOString().slice(0, 10),
        time: data.time || '09:00',
        type: (data.type as any) || 'other',
        repeat: (data.repeat as any) || 'none',
        enabled: data.enabled !== undefined ? data.enabled : true,
        description: data.description || '',
      }
      reminders.value.unshift(newReminder)
      total.value++
      return newReminder
    } finally {
      loading.value = false
    }
  }

  async function updateReminder(id: string | number, data: Partial<Reminder>) {
    loading.value = true
    try {
      const result = await reminderApi.updateReminder(id, data)
      const index = reminders.value.findIndex(r => r.id === id)
      if (index !== -1) {
        reminders.value[index] = result
      }
      if (currentReminder.value?.id === id) {
        currentReminder.value = result
      }
      return result
    } catch (e) {
      console.warn('使用mock更新提醒:', e)
      const index = reminders.value.findIndex(r => r.id === id)
      if (index !== -1) {
        reminders.value[index] = { ...reminders.value[index], ...data }
        if (currentReminder.value?.id === id) {
          currentReminder.value = reminders.value[index]
        }
        return reminders.value[index]
      }
      return null
    } finally {
      loading.value = false
    }
  }

  async function deleteReminder(id: string | number) {
    loading.value = true
    try {
      await reminderApi.deleteReminder(id)
      reminders.value = reminders.value.filter(r => r.id !== id)
      if (currentReminder.value?.id === id) {
        currentReminder.value = null
      }
    } catch (e) {
      console.warn('使用mock删除提醒:', e)
      reminders.value = reminders.value.filter(r => r.id !== id)
      total.value--
      if (currentReminder.value?.id === id) {
        currentReminder.value = null
      }
    } finally {
      loading.value = false
    }
  }

  async function toggleReminder(id: string | number, enabled: boolean) {
    try {
      const result = await reminderApi.toggleReminder(id, enabled)
      const index = reminders.value.findIndex(r => r.id === id)
      if (index !== -1) {
        reminders.value[index].enabled = enabled
      }
      return result
    } catch (e) {
      console.warn('使用mock切换提醒状态:', e)
      const index = reminders.value.findIndex(r => r.id === id)
      if (index !== -1) {
        reminders.value[index].enabled = enabled
      }
      return reminders.value[index]
    }
  }

  async function snoozeReminder(id: string | number, minutes: number) {
    try {
      const result = await reminderApi.snoozeReminder(id, minutes)
      const index = reminders.value.findIndex(r => r.id === id)
      if (index !== -1) {
        reminders.value[index] = result
      }
      if (currentReminder.value?.id === id) {
        currentReminder.value = result
      }
      return result
    } catch (e) {
      console.warn('使用mock延后提醒:', e)
      const index = reminders.value.findIndex(r => r.id === id)
      if (index !== -1) {
        const reminder = reminders.value[index]
        const newTime = dayjs(`${reminder.date} ${reminder.time}`).add(minutes, 'minute')
        reminder.date = newTime.format('YYYY-MM-DD')
        reminder.time = newTime.format('HH:mm')
        reminder.snoozeMinutes = minutes
        reminder.nextRemindAt = newTime.toISOString()
        if (currentReminder.value?.id === id) {
          currentReminder.value = reminder
        }
        return reminder
      }
      return null
    }
  }

  function clearCurrentReminder() {
    currentReminder.value = null
  }

  return {
    reminders,
    todayReminders,
    currentReminder,
    loading,
    total,
    page,
    pageSize,
    enabledReminders,
    disabledReminders,
    fetchReminders,
    fetchTodayReminders,
    fetchReminderDetail,
    createReminder,
    updateReminder,
    deleteReminder,
    toggleReminder,
    snoozeReminder,
    clearCurrentReminder,
  }
})
