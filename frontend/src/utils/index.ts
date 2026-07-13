import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/zh-cn'

dayjs.extend(relativeTime)
dayjs.locale('zh-cn')

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function formatDate(date: string | Date | dayjs.Dayjs, format: string = 'YYYY-MM-DD'): string {
  return dayjs(date).format(format)
}

export function formatDateTime(date: string | Date | dayjs.Dayjs): string {
  return dayjs(date).format('YYYY-MM-DD HH:mm')
}

export function formatTime(date: string | Date | dayjs.Dayjs): string {
  return dayjs(date).format('HH:mm')
}

export function formatRelativeTime(date: string | Date | dayjs.Dayjs): string {
  return dayjs(date).fromNow()
}

export function formatBirthday(date: string | Date): string {
  return dayjs(date).format('YYYY年MM月DD日')
}

export function calculateAge(birthDate: string | Date): {
  years: number
  months: number
  days: number
  totalDays: number
  text: string
} {
  const birth = dayjs(birthDate)
  const now = dayjs()
  
  let years = now.year() - birth.year()
  let months = now.month() - birth.month()
  let days = now.date() - birth.date()

  if (days < 0) {
    months--
    const lastMonth = now.subtract(1, 'month')
    days += lastMonth.daysInMonth()
  }

  if (months < 0) {
    years--
    months += 12
  }

  const totalDays = now.diff(birth, 'day')
  
  let text = ''
  if (years > 0) {
    text += `${years}岁`
  }
  if (months > 0) {
    text += `${months}个月`
  }
  if (years === 0 && months === 0) {
    text = `${days}天`
  } else if (days > 0 && years < 3) {
    text += `${days}天`
  }

  return {
    years,
    months,
    days,
    totalDays,
    text,
  }
}

export function getDayOfWeek(date: string | Date): string {
  const days = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  return days[dayjs(date).day()]
}

export function isToday(date: string | Date): boolean {
  return dayjs(date).isSame(dayjs(), 'day')
}

export function isTomorrow(date: string | Date): boolean {
  return dayjs(date).isSame(dayjs().add(1, 'day'), 'day')
}

export function isYesterday(date: string | Date): boolean {
  return dayjs(date).isSame(dayjs().subtract(1, 'day'), 'day')
}

export function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}

export function generateId(): string {
  return Date.now().toString(36) + Math.random().toString(36).substr(2)
}

export function debounce<T extends (...args: any[]) => any>(
  fn: T,
  delay: number
): (...args: Parameters<T>) => void {
  let timer: ReturnType<typeof setTimeout> | null = null
  return function (...args: Parameters<T>) {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      fn(...args)
    }, delay)
  }
}

export function throttle<T extends (...args: any[]) => any>(
  fn: T,
  delay: number
): (...args: Parameters<T>) => void {
  let last = 0
  return function (...args: Parameters<T>) {
    const now = Date.now()
    if (now - last >= delay) {
      last = now
      fn(...args)
    }
  }
}

export function deepClone<T>(obj: T): T {
  if (obj === null || typeof obj !== 'object') {
    return obj
  }
  if (Array.isArray(obj)) {
    return obj.map(item => deepClone(item)) as unknown as T
  }
  const cloned = {} as T
  for (const key in obj) {
    if (Object.prototype.hasOwnProperty.call(obj, key)) {
      cloned[key] = deepClone(obj[key])
    }
  }
  return cloned
}

export const storage = {
  get<T>(key: string, defaultValue?: T): T | null {
    try {
      const value = window.localStorage.getItem(key)
      if (value === null) return defaultValue ?? null
      return JSON.parse(value) as T
    } catch {
      return defaultValue ?? null
    }
  },

  set(key: string, value: any): void {
    try {
      window.localStorage.setItem(key, JSON.stringify(value))
    } catch (e) {
      console.error('localStorage set error:', e)
    }
  },

  remove(key: string): void {
    window.localStorage.removeItem(key)
  },

  clear(): void {
    window.localStorage.clear()
  },
}

export const sessionStore = {
  get<T>(key: string, defaultValue?: T): T | null {
    try {
      const value = window.sessionStorage.getItem(key)
      if (value === null) return defaultValue ?? null
      return JSON.parse(value) as T
    } catch {
      return defaultValue ?? null
    }
  },

  set(key: string, value: any): void {
    try {
      window.sessionStorage.setItem(key, JSON.stringify(value))
    } catch (e) {
      console.error('sessionStorage set error:', e)
    }
  },

  remove(key: string): void {
    window.sessionStorage.removeItem(key)
  },

  clear(): void {
    window.sessionStorage.clear()
  },
}

export { dayjs }
