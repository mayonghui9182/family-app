<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Home, CheckSquare, Baby, User } from 'lucide-vue-next'

const route = useRoute()
const router = useRouter()

const tabs = [
  { path: '/', name: '首页', icon: Home },
  { path: '/todos', name: '待办', icon: CheckSquare },
  { path: '/baby', name: '宝宝', icon: Baby },
  { path: '/profile', name: '我的', icon: User },
]

const showTabBar = computed(() => {
  return !route.meta?.hideTabBar
})

const isActive = (path: string) => {
  if (path === '/') {
    return route.path === '/'
  }
  return route.path.startsWith(path)
}

const handleTabClick = (path: string) => {
  if (route.path !== path) {
    router.push(path)
  }
}
</script>

<template>
  <div class="min-h-screen bg-app flex flex-col">
    <main class="flex-1 overflow-auto">
      <router-view v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>

    <nav
      v-if="showTabBar"
      class="fixed bottom-0 left-0 right-0 bg-white border-t border-gray-100 safe-area-bottom z-50"
    >
      <div class="flex items-center justify-around py-2">
        <button
          v-for="tab in tabs"
          :key="tab.path"
          @click="handleTabClick(tab.path)"
          class="flex flex-col items-center gap-1 px-2 py-1 min-w-0 flex-1 transition-all duration-300"
          :class="isActive(tab.path) ? 'text-primary-500' : 'text-gray-400'"
        >
          <div class="relative">
            <component
              :is="tab.icon"
              :size="24"
              :class="isActive(tab.path) ? 'scale-110' : ''"
              class="transition-transform duration-300"
            />
            <div
              v-if="isActive(tab.path)"
              class="absolute -bottom-1 left-1/2 -translate-x-1/2 w-1 h-1 rounded-full bg-primary-500"
            ></div>
          </div>
          <span
            class="text-xs font-medium transition-all duration-300"
            :class="isActive(tab.path) ? 'text-primary-500 font-semibold' : 'text-gray-400'"
          >
            {{ tab.name }}
          </span>
        </button>
      </div>
    </nav>
  </div>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
