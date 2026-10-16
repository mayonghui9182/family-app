import { defineStore } from 'pinia'
import { cancelFamilyRequests } from '../api/familyRequests'

function parseJSON(value, fallback) {
  if (!value) return fallback
  try {
    return JSON.parse(value)
  } catch (e) {
    return fallback
  }
}

export const useFamilyStore = defineStore('family', {
  state: () => ({
    currentFamily: parseJSON(localStorage.getItem('currentFamily'), null)
      || (localStorage.getItem('currentFamilyId') ? { id: localStorage.getItem('currentFamilyId') } : null),
    members: []
  }),
  getters: {
    currentFamilyId: (state) => {
      if (state.currentFamily && state.currentFamily.id) {
        return String(state.currentFamily.id)
      }
      return ''
    }
  },
  actions: {
    reconcile(families) {
      const selected = families.find(family => String(family.id) === this.currentFamilyId)
      this.setCurrent(selected || families[0] || null)
    },
    setCurrent(family) {
      if (String(family?.id || '') !== this.currentFamilyId) {
        cancelFamilyRequests()
        this.members = []
      }
      this.currentFamily = family || null
      if (family && family.id) {
        localStorage.setItem('currentFamilyId', String(family.id))
        localStorage.setItem('currentFamily', JSON.stringify(family))
      } else {
        localStorage.removeItem('currentFamilyId')
        localStorage.removeItem('currentFamily')
      }
    },
    loadCurrentFamilyId() {
      return localStorage.getItem('currentFamilyId') || ''
    },
    clear() {
      cancelFamilyRequests()
      this.currentFamily = null
      this.members = []
      localStorage.removeItem('currentFamilyId')
      localStorage.removeItem('currentFamily')
    }
  }
})
