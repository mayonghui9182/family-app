// frontend/src/stores/profiles.js（Pinia：缓存当前家庭 profiles；统一 A 排序；权限 guard）
import { defineStore } from 'pinia'
import { listProfiles, createProfile, updateProfile, deleteProfile } from '../api/profiles.js';
import { typePrioritySort } from '../utils/profileRender.js';

export const useProfilesStore = defineStore('profiles', {
  state: () => ({
    profiles: [],     // PersonProfileResponse[]
    loading: false,
    error: null,
    _loadedAt: 0      // 5min 缓存
  }),
  getters: {
    sortedProfiles: (s) => typePrioritySort(s.profiles),
    role: () => localStorage.getItem('currentFamilyRole') || 'MEMBER', // OWNER/ADULT/CHILD/MEMBER
    canEditProfile: (s) => s.role === 'OWNER' || s.role === 'ADULT',
    canDeleteProfile: (s) => s.role === 'OWNER'
  },
  actions: {
    async fetchList({ force = false } = {}) {
      const isStale = Date.now() - this._loadedAt > 5*60*1000;
      if (!force && this.profiles.length && !isStale) return this.profiles;
      this.loading = true; this.error = null;
      try {
        const data = await listProfiles();
        this.profiles = Array.isArray(data) ? data : (data.items || []);
        this._loadedAt = Date.now();
        return this.profiles;
      } catch (e) { this.error = e.message; throw e; } finally { this.loading = false; }
    },
    async create(payload) {
      const created = await createProfile(payload);
      this.profiles.push(created);
      this._loadedAt = 0;
      return created;
    },
    async update(id, payload) {
      const updated = await updateProfile(id, payload);
      const idx = this.profiles.findIndex(p => p.id === id);
      if (idx >= 0) this.profiles.splice(idx, 1, updated);
      this._loadedAt = 0;
      return updated;
    },
    async remove(id) {
      await deleteProfile(id); // 可能抛 40901
      this.profiles = this.profiles.filter(p => p.id !== id);
      this._loadedAt = 0;
    }
  }
});

export default useProfilesStore;
