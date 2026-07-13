<script setup lang="ts">
import { onMounted } from 'vue'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()

onMounted(() => {
  appStore.initTheme()
  appStore.initCity()
})
</script>

<template>
  <router-view />
</template>
