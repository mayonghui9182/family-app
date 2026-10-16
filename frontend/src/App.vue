<template>
  <RouterView />
</template>

<script setup>
import { onMounted } from 'vue'
import { useFontStore } from './stores/font'

const fontStore = useFontStore()
onMounted(() => {
  fontStore.apply()
})
</script>

<style>
html,
body,
#app {
  margin: 0;
  padding: 0;
  height: 100%;
}
#app {
  font-family: -apple-system, system-ui, "PingFang SC", "Microsoft YaHei", sans-serif;
  color: #303133;
}
</style>