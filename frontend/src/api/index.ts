import { get, post, put, del, request } from './request'
import type {
  Weather,
  ForecastDay,
  LifeIndex,
  TravelGuide,
  Reminder,
  Todo,
  Baby,
  BabyGrowthRecord,
  BabyDailyRecord,
  Milestone,
  PageResult,
  UserInfo,
  LoginResult,
  FamilyInfo,
  FamilyMember,
  InviteCode,
  BabyVaccine,
  VaccineStats,
  Album,
  Photo,
  HouseholdItem,
  ItemRecord,
  ItemStats,
} from '@/types'

export const weatherApi = {
  getCurrentWeather(city: string): Promise<Weather> {
    return get<Weather>('/weather/current', { city })
  },

  getForecast(city: string, days: number = 7): Promise<ForecastDay[]> {
    return get<ForecastDay[]>('/weather/forecast', { city, days })
  },

  getLifeIndex(city: string): Promise<LifeIndex[]> {
    return get<LifeIndex[]>('/weather/life-index', { city })
  },

  searchCities(keyword: string): Promise<{ name: string; adcode: string }[]> {
    return get('/weather/cities', { keyword })
  },
}

export const travelApi = {
  getTravelList(params?: {
    page?: number
    pageSize?: number
    category?: string
    keyword?: string
  }): Promise<PageResult<TravelGuide>> {
    return get<PageResult<TravelGuide>>('/travel/list', params)
  },

  getTravelDetail(id: string | number): Promise<TravelGuide> {
    return get<TravelGuide>(`/travel/${id}`)
  },

  getHotDestinations(): Promise<TravelGuide[]> {
    return get<TravelGuide[]>('/travel/hot')
  },

  getRecommendations(): Promise<TravelGuide[]> {
    return get<TravelGuide[]>('/travel/recommendations')
  },

  getCategories(): Promise<{ id: string; name: string; icon: string }[]> {
    return get('/travel/categories')
  },
}

export const reminderApi = {
  getReminderList(params?: {
    page?: number
    pageSize?: number
    type?: string
    enabled?: boolean
  }): Promise<PageResult<Reminder>> {
    return get<PageResult<Reminder>>('/reminders', params)
  },

  getReminderDetail(id: string | number): Promise<Reminder> {
    return get<Reminder>(`/reminders/${id}`)
  },

  createReminder(data: Partial<Reminder>): Promise<Reminder> {
    return post<Reminder>('/reminders', data)
  },

  updateReminder(id: string | number, data: Partial<Reminder>): Promise<Reminder> {
    return put<Reminder>(`/reminders/${id}`, data)
  },

  deleteReminder(id: string | number): Promise<void> {
    return del(`/reminders/${id}`)
  },

  toggleReminder(id: string | number, enabled: boolean): Promise<Reminder> {
    return put<Reminder>(`/reminders/${id}/toggle`, { enabled })
  },

  getTodayReminders(): Promise<Reminder[]> {
    return get<Reminder[]>('/reminders/today')
  },

  snoozeReminder(id: string | number, minutes: number): Promise<Reminder> {
    return put<Reminder>(`/reminders/${id}/snooze`, { minutes })
  },
}

export const todoApi = {
  getTodoList(params?: {
    page?: number
    pageSize?: number
    completed?: boolean
    priority?: string
    category?: string
  }): Promise<PageResult<Todo>> {
    return get<PageResult<Todo>>('/todos', params)
  },

  getTodoDetail(id: string | number): Promise<Todo> {
    return get<Todo>(`/todos/${id}`)
  },

  createTodo(data: Partial<Todo>): Promise<Todo> {
    return post<Todo>('/todos', data)
  },

  updateTodo(id: string | number, data: Partial<Todo>): Promise<Todo> {
    return put<Todo>(`/todos/${id}`, data)
  },

  deleteTodo(id: string | number): Promise<void> {
    return del(`/todos/${id}`)
  },

  toggleTodo(id: string | number, completed: boolean): Promise<Todo> {
    return put<Todo>(`/todos/${id}/toggle`, { completed })
  },

  getTodayTodos(): Promise<Todo[]> {
    return get<Todo[]>('/todos/today')
  },

  getStats(): Promise<{
    total: number
    completed: number
    active: number
    today: number
  }> {
    return get('/todos/stats')
  },
}

export const babyApi = {
  getBabyInfo(id?: string | number): Promise<Baby> {
    return get<Baby>(id ? `/baby/${id}` : '/baby/current')
  },

  updateBabyInfo(id: string | number, data: Partial<Baby>): Promise<Baby> {
    return put<Baby>(`/baby/${id}`, data)
  },

  getGrowthRecords(babyId: string | number, params?: {
    page?: number
    pageSize?: number
  }): Promise<PageResult<BabyGrowthRecord>> {
    return get<PageResult<BabyGrowthRecord>>(`/baby/${babyId}/growth`, params)
  },

  addGrowthRecord(babyId: string | number, data: Partial<BabyGrowthRecord>): Promise<BabyGrowthRecord> {
    return post<BabyGrowthRecord>(`/baby/${babyId}/growth`, data)
  },

  deleteGrowthRecord(babyId: string | number, recordId: string | number): Promise<void> {
    return del(`/baby/${babyId}/growth/${recordId}`)
  },

  getDailyRecords(babyId: string | number, date?: string): Promise<BabyDailyRecord[]> {
    return get<BabyDailyRecord[]>(`/baby/${babyId}/daily`, { date })
  },

  addDailyRecord(babyId: string | number, data: Partial<BabyDailyRecord>): Promise<BabyDailyRecord> {
    return post<BabyDailyRecord>(`/baby/${babyId}/daily`, data)
  },

  deleteDailyRecord(babyId: string | number, recordId: string | number): Promise<void> {
    return del(`/baby/${babyId}/daily/${recordId}`)
  },

  getMilestones(babyId: string | number): Promise<Milestone[]> {
    return get<Milestone[]>(`/baby/${babyId}/milestones`)
  },

  updateMilestone(babyId: string | number, milestoneId: string | number, achieved: boolean): Promise<Milestone> {
    return put<Milestone>(`/baby/${babyId}/milestones/${milestoneId}`, { achieved })
  },
}

export const authApi = {
  createFamily(data: { familyName: string; userName: string }): Promise<LoginResult> {
    return post('/auth/create-family', data)
  },
  joinFamily(data: { inviteCode: string; userName: string }): Promise<LoginResult> {
    return post('/auth/join-family', data)
  },
}

export const fileApi = {
  uploadVoice(file: File): Promise<{ url: string; size: number }> {
    const formData = new FormData()
    formData.append('file', file)
    return post('/uploads/voice', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  uploadImage(file: File): Promise<{ url: string; thumbnailUrl?: string; size: number }> {
    const formData = new FormData()
    formData.append('file', file)
    return post('/uploads/image', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  }
}

export const albumApi = {
  getAlbumList(babyId: string | number): Promise<Album[]> {
    return get<Album[]>(`/baby/${babyId}/albums`)
  },
  createAlbum(babyId: string | number, title: string, description?: string): Promise<Album> {
    return post<Album>(`/baby/${babyId}/albums`, { title, description })
  },
  updateAlbum(babyId: string | number, albumId: string | number, title: string, description?: string): Promise<Album> {
    return put<Album>(`/baby/${babyId}/albums/${albumId}`, { title, description })
  },
  deleteAlbum(babyId: string | number, albumId: string | number): Promise<void> {
    return del(`/baby/${babyId}/albums/${albumId}`)
  },
  getPhotoList(babyId: string | number, albumId: string | number, page?: number, size?: number): Promise<PageResult<Photo>> {
    return get<PageResult<Photo>>(`/baby/${babyId}/albums/${albumId}/photos`, { page, size })
  },
  uploadPhotos(babyId: string | number, albumId: string | number, data: any): Promise<Photo[]> {
    return post<Photo[]>(`/baby/${babyId}/albums/${albumId}/photos`, data)
  },
  updatePhoto(babyId: string | number, photoId: string | number, data: Partial<Photo>): Promise<Photo> {
    return put<Photo>(`/baby/${babyId}/photos/${photoId}`, data)
  },
  deletePhoto(babyId: string | number, photoId: string | number): Promise<void> {
    return del(`/baby/${babyId}/photos/${photoId}`)
  },
  setCover(babyId: string | number, albumId: string | number, photoId: string | number): Promise<void> {
    return put(`/baby/${babyId}/albums/${albumId}/cover`, { photoId })
  },
}

export const vaccineApi = {
  getVaccineList(type?: 'free' | 'paid' | 'all'): Promise<any[]> {
    return get('/vaccine/list', { type })
  },
  getBabyVaccines(babyId: string | number, status?: string): Promise<BabyVaccine[]> {
    return get<BabyVaccine[]>(`/baby/${babyId}/vaccines`, { status })
  },
  getVaccineStats(babyId: string | number): Promise<VaccineStats> {
    return get<VaccineStats>(`/baby/${babyId}/vaccines/stats`)
  },
  markVaccinated(babyId: string | number, id: string | number, data: any): Promise<BabyVaccine> {
    return put<BabyVaccine>(`/baby/${babyId}/vaccines/${id}/vaccinated`, data)
  },
  updatePlannedDate(babyId: string | number, id: string | number, plannedDate: string): Promise<void> {
    return put(`/baby/${babyId}/vaccines/${id}/planned-date`, { plannedDate })
  },
  skipVaccine(babyId: string | number, id: string | number, reason: string): Promise<void> {
    return put(`/baby/${babyId}/vaccines/${id}/skip`, { reason })
  },
  toggleRemind(babyId: string | number, id: string | number, enabled: number): Promise<void> {
    return put(`/baby/${babyId}/vaccines/${id}/remind`, { enabled })
  },
  generatePlan(babyId: string | number): Promise<void> {
    return post(`/baby/${babyId}/vaccines/generate`)
  },
}

export const familyApi = {
  getFamilyInfo(): Promise<FamilyInfo> {
    return get<FamilyInfo>('/family/info')
  },
  getMembers(): Promise<FamilyMember[]> {
    return get<FamilyMember[]>('/family/members')
  },
  updateMemberRole(userId: string | number, role: string): Promise<void> {
    return put(`/family/members/${userId}/role`, { role })
  },
  removeMember(userId: string | number): Promise<void> {
    return del(`/family/members/${userId}`)
  },
  generateInviteCode(maxCount?: number, expireHours?: number): Promise<InviteCode> {
    return post<InviteCode>('/family/invite/generate', { maxCount, expireHours })
  },
  getInviteList(): Promise<InviteCode[]> {
    return get<InviteCode[]>('/family/invite/list')
  },
  disableInvite(inviteId: string | number): Promise<void> {
    return put(`/family/invite/${inviteId}/disable`)
  },
}

export const speechApi = {
  recognize(file: File): Promise<{ text: string }> {
    const formData = new FormData()
    formData.append('file', file)
    return post('/speech/recognize', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  synthesize(text: string): Promise<Blob> {
    return request({
      url: '/speech/synthesize',
      method: 'GET',
      params: { text },
      responseType: 'blob'
    })
  },
}

export const pushApi = {
  registerDevice(deviceToken: string, deviceType: string, deviceName?: string): Promise<void> {
    return post('/push/register', { deviceToken, deviceType, deviceName })
  },
  unregisterDevice(deviceToken: string): Promise<void> {
    return post('/push/unregister', { deviceToken })
  },
  getDevices(): Promise<any[]> {
    return get('/push/devices')
  },
}

export const itemApi = {
  getItemList(category?: string): Promise<HouseholdItem[]> {
    return get<HouseholdItem[]>('/items', { category })
  },
  getItemDetail(id: string | number): Promise<HouseholdItem> {
    return get<HouseholdItem>(`/items/${id}`)
  },
  createItem(data: Partial<HouseholdItem>): Promise<HouseholdItem> {
    return post<HouseholdItem>('/items', data)
  },
  updateItem(id: string | number, data: Partial<HouseholdItem>): Promise<HouseholdItem> {
    return put<HouseholdItem>(`/items/${id}`, data)
  },
  deleteItem(id: string | number): Promise<void> {
    return del(`/items/${id}`)
  },
  stockIn(id: string | number, data: any): Promise<HouseholdItem> {
    return post<HouseholdItem>(`/items/${id}/stock-in`, data)
  },
  stockOut(id: string | number, data: any): Promise<HouseholdItem> {
    return post<HouseholdItem>(`/items/${id}/stock-out`, data)
  },
  getRecordList(id: string | number, page?: number, size?: number): Promise<PageResult<ItemRecord>> {
    return get<PageResult<ItemRecord>>(`/items/${id}/records`, { page, size })
  },
  getStats(): Promise<ItemStats> {
    return get<ItemStats>('/items/stats')
  },
  getLowStockItems(): Promise<HouseholdItem[]> {
    return get<HouseholdItem[]>('/items/low-stock')
  },
}

export default {
  weather: weatherApi,
  travel: travelApi,
  reminder: reminderApi,
  todo: todoApi,
  baby: babyApi,
  vaccine: vaccineApi,
  album: albumApi,
  auth: authApi,
  file: fileApi,
  family: familyApi,
  speech: speechApi,
  push: pushApi,
  item: itemApi,
}
