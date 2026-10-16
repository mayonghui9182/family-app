import { defineStore } from 'pinia'

const STORAGE_KEY = 'ui.fontScale'
const SCALE_ENUM = ['S', 'M', 'L']
const DEFAULT_SCALE = 'M'

function readScaleFromStorage() {
  const raw = (typeof localStorage !== 'undefined') ? localStorage.getItem(STORAGE_KEY) : null
  if (raw && SCALE_ENUM.includes(raw)) return raw
  return DEFAULT_SCALE
}

function applyHtmlClass(scale) {
  if (typeof document === 'undefined') return
  const html = document.documentElement
  html.classList.remove('font-scale-s', 'font-scale-m', 'font-scale-l')
  html.classList.add('font-scale-' + (scale || 'm').toLowerCase())
}

export const useFontStore = defineStore('font', {
  state: () => ({
    currentScale: readScaleFromStorage()
  }),
  getters: {
    scaleLabel: (state) => {
      const map = { S: '标准', M: '大', L: '特大' }
      return map[state.currentScale] || '大'
    },
    scaleMultiplier: () => {
      return { S: 1.0, M: 1.125, L: 1.3125 }
    }
  },
  actions: {
    setScale(next) {
      const value = SCALE_ENUM.includes(next) ? next : DEFAULT_SCALE
      this.currentScale = value
      if (typeof localStorage !== 'undefined') {
        localStorage.setItem(STORAGE_KEY, value)
      }
      applyHtmlClass(value)
    },
    apply() {
      const value = readScaleFromStorage()
      this.currentScale = value
      applyHtmlClass(value)
    }
  }
})