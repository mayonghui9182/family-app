<template>
  <slot v-if="optional || (ready && familyStore.currentFamilyId)" />
  <section v-else class="family-guide" aria-live="polite">
    <p v-if="loading" role="status">正在读取您的家庭，请稍候…</p>
    <template v-else-if="failed">
      <h1>暂时无法读取家庭</h1>
      <p>请检查网络后重试。</p>
      <button type="button" @click="load">重新读取</button>
    </template>
    <template v-else>
      <h1>先选择一个家庭</h1>
      <p>您可以创建自己的家庭，也可以用家人分享的邀请码加入。</p>
      <RouterLink to="/families">创建或加入家庭</RouterLink>
    </template>
  </section>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useRoute } from 'vue-router'
import { familyApi } from '../api/family'
import { useAuthStore } from '../stores/auth'
import { useFamilyStore } from '../stores/family'

const route = useRoute()
const authStore = useAuthStore()
const familyStore = useFamilyStore()
const optional = computed(() => route.meta.familyOptional === true)
const loading = ref(true)
const failed = ref(false)
const ready = ref(false)
let active = true
onBeforeUnmount(() => { active = false })

async function load() {
  loading.value = true
  failed.value = false
  const token = authStore.token
  try {
    const families = (await familyApi.list()) || []
    // 离开布局或切换账号后，不恢复旧账号的家庭选择。
    if (!active || authStore.token !== token) return
    authStore.setFamilies(families)
    familyStore.reconcile(families)
    ready.value = true
  } catch {
    if (active) failed.value = true
  } finally {
    if (active) loading.value = false
  }
}
onMounted(load)
</script>

<style scoped>
.family-guide { max-width: 560px; margin: 24px auto; padding: 20px; background: white; border-radius: 12px; color: #303133; }
h1 { font-size: 24px; }
p { font-size: 18px; line-height: 1.7; }
a, button { display: inline-block; box-sizing: border-box; padding: 12px 18px; min-height: 48px; font-size: 18px; color: white; background: #1d4ed8; border: 0; border-radius: 8px; text-decoration: none; cursor: pointer; }
</style>
