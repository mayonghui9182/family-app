export interface UserInfo {
  id: string
  name: string
  avatar?: string
  email?: string
  phone?: string
}

export interface FamilyInfo {
  id: string | number
  name: string
  inviteCode: string
  memberCount: number
}

export interface FamilyMember {
  id: string | number
  name: string
  avatar?: string
  role: 'admin' | 'member'
  lastLoginAt?: string
  joinedAt?: string
}

export interface InviteCode {
  id: string | number
  code: string
  creatorName: string
  usedCount: number
  maxCount: number
  status: 'active' | 'expired' | 'disabled'
  expireAt: string
  createdAt: string
}

export interface LoginResult {
  token: string
  userId: string | number
  familyId: string | number
  userName: string
  familyName: string
  role: string
}

export interface Weather {
  city: string
  temperature: number
  feelsLike: number
  humidity: number
  windSpeed: number
  windDirection: string
  condition: string
  icon: string
  pressure: number
  visibility: number
  uvIndex: number
  airQuality: string
  aqi: number
}

export interface ForecastDay {
  date: string
  dayOfWeek: string
  high: number
  low: number
  condition: string
  icon: string
  precipitation: number
  humidity: number
  windSpeed: number
}

export interface LifeIndex {
  name: string
  level: string
  description: string
  icon: string
}

export interface TravelGuide {
  id: string | number
  name: string
  location: string
  description: string
  image: string
  images?: string[]
  rating: number
  reviewCount: number
  price: string
  minPrice: number
  tags: string[]
  category: string
  openTime: string
  suggestedDuration: string
  suitableFor: string[]
  transportation: TravelTransport[]
  highlights: string[]
  tips: string[]
}

export interface TravelTransport {
  type: string
  icon: string
  name: string
  description: string
}

export interface Reminder {
  id: string | number
  title: string
  date: string
  time: string
  type: ReminderType
  repeat: ReminderRepeat
  enabled: boolean
  description?: string
  notification?: boolean
  sound?: string
  todoId?: string | number
  timeType?: 'absolute' | 'relative'
  nextRemindAt?: string
  remindCount?: number
  maxRemindCount?: number
  snoozeMinutes?: number
}

export type ReminderType = 'birthday' | 'bill' | 'health' | 'social' | 'work' | 'other'

export type ReminderRepeat = 'none' | 'daily' | 'weekly' | 'monthly' | 'yearly'

export interface Todo {
  id: string | number
  title: string
  description?: string
  completed: boolean
  priority: TodoPriority
  dueDate?: string
  dueTime?: string
  tags?: string[]
  category?: string
  createdAt: string
  completedAt?: string
  contentType: 'text' | 'voice'
  voiceUrl?: string
  voiceDuration?: number
  hasReminder?: boolean
  remindTimeType?: 'absolute' | 'relative'
  remindRelativeMinutes?: number
  repeatType?: 'none' | 'daily' | 'weekly' | 'monthly'
}

export type TodoPriority = 'low' | 'medium' | 'high'

export interface Baby {
  id: string | number
  name: string
  nickname?: string
  gender: 'boy' | 'girl'
  birthDate: string
  birthTime?: string
  birthWeight?: number
  birthHeight?: number
  avatar?: string
  bloodType?: string
  zodiac?: string
}

export interface BabyGrowthRecord {
  id: string | number
  babyId: string | number
  date: string
  height?: number
  weight?: number
  headCircumference?: number
  note?: string
}

export interface BabyDailyRecord {
  id: string | number
  babyId: string | number
  date: string
  type: BabyRecordType
  time: string
  description: string
  amount?: number
  unit?: string
  note?: string
}

export type BabyRecordType = 'feeding' | 'sleep' | 'diaper' | 'bath' | 'play' | 'medicine' | 'other'

export interface Milestone {
  id: string | number
  babyId: string | number
  title: string
  description: string
  achievedDate?: string
  expectedAge: string
  category: string
  achieved: boolean
  icon?: string
}

export interface ApiResponse<T = any> {
  code: number
  message: string
  data: T
}

export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
  totalPages: number
}

export interface BabyVaccine {
  id: string | number
  vaccineId: string | number
  vaccineName: string
  shortName: string
  type: 'free' | 'paid'
  doseNumber: number
  totalDoses: number
  preventDisease: string
  plannedDate: string
  actualDate?: string
  status: 'pending' | 'completed' | 'skipped' | 'delayed'
  injectionSite?: string
  hospital?: string
  batchNumber?: string
  adverseReaction?: string
  remark?: string
  remindEnabled: number
  remindDaysBefore: number
  reminded: number
  description?: string
  precautions?: string
  isOverdue: boolean
  daysLeft: number
}

export interface VaccineStats {
  total: number
  completed: number
  pending: number
  skipped: number
  overdue: number
  nextVaccineName?: string
  nextVaccineDate?: string
  progress: number
}

export interface Album {
  id: string | number
  babyId: string | number
  title: string
  description?: string
  coverUrl?: string
  photoCount: number
  createdAt: string
  updatedAt: string
}

export interface Photo {
  id: string | number
  babyId: string | number
  albumId: string | number
  url: string
  thumbnailUrl?: string
  title?: string
  description?: string
  date?: string
  size?: number
  createdAt: string
}

export interface TabItem {
  path: string
  name: string
  icon: any
  activeIcon?: any
}

export interface HouseholdItem {
  id: string | number
  name: string
  category: string
  categoryName: string
  unit: string
  totalQuantity: number
  warningQuantity: number
  icon?: string
  remark?: string
  isLowStock: boolean
  createTime?: string
}

export interface ItemRecord {
  id: string | number
  itemId: string | number
  itemName: string
  type: 'in' | 'out'
  typeName: string
  quantity: number
  unit: string
  recordDate: string
  operatorId?: string | number
  operatorName?: string
  source?: string
  price?: number
  totalPrice?: number
  remark?: string
  createTime?: string
}

export interface ItemStats {
  totalItems: number
  lowStockItems: number
  categories: { category: string; categoryName: string; count: number }[]
  monthlyIn: number
  monthlyOut: number
}
