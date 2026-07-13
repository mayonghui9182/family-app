import { defineStore } from 'pinia'
import { ref } from 'vue'
import { storage } from '@/utils'

export const useAppStore = defineStore('app', () => {
  const theme = ref<'light' | 'dark'>('light')
  const currentCity = ref('北京市')

  function setTheme(mode: 'light' | 'dark') {
    theme.value = mode
    if (mode === 'dark') {
      document.documentElement.classList.add('dark')
    } else {
      document.documentElement.classList.remove('dark')
    }
    storage.set('theme', mode)
  }

  function toggleTheme() {
    setTheme(theme.value === 'light' ? 'dark' : 'light')
  }

  function initTheme() {
    const savedTheme = storage.get<'light' | 'dark'>('theme')
    if (savedTheme) {
      setTheme(savedTheme)
    }
  }

  function setCity(city: string) {
    currentCity.value = city
    storage.set('currentCity', city)
  }

  function initCity() {
    const savedCity = storage.get<string>('currentCity')
    if (savedCity) {
      currentCity.value = savedCity
    }
  }

  return {
    theme,
    currentCity,
    setTheme,
    toggleTheme,
    initTheme,
    setCity,
    initCity,
  }
})
