<template>
  <a href="#main-content" class="skip-to-content" aria-label="按回车跳转到主内容区，跳过顶部菜单">跳转到主内容</a>
  <el-container class="app-layout">
    <el-header class="app-header-wrap">
      <AppHeader />
    </el-header>
    <el-container>
      <el-aside v-if="!isMobile" width="220px" class="app-aside-wrap">
        <AppSider />
      </el-aside>
      <el-main class="app-main-wrap" :class="{ 'app-main-mobile': isMobile }">
        <main id="main-content" role="main" aria-label="页面主内容">
          <FamilyGate>
            <RouterView :key="route.meta.familyOptional ? route.name : familyStore.currentFamilyId" />
          </FamilyGate>
        </main>
      </el-main>
    </el-container>
    <!-- D2: 移动端底部 Tab（仅 ≤768px 挂载） -->
    <BottomTabBar v-if="isMobile" />
  </el-container>
</template>

<script setup>
import { watch } from 'vue'
import { useRoute } from 'vue-router'
import { useFamilyStore } from '../stores/family'
import { useDecomposeSseStore } from '../stores/decomposeSse'
import { useSummaryStore } from '../stores/summary'
import { useTemplatesStore } from '../stores/templates'
import { useProfilesStore } from '../stores/profiles'
import AppHeader from '../components/AppHeader.vue'
import FamilyGate from '../components/FamilyGate.vue'
import AppSider from '../components/AppSider.vue'
import BottomTabBar from '../components/BottomTabBar.vue'
import { useIsMobile } from '../composables/useIsMobile.js'

const isMobile = useIsMobile()
const route = useRoute()
const familyStore = useFamilyStore()
const chat = useDecomposeSseStore()
const summary = useSummaryStore()
const templates = useTemplatesStore()
const profiles = useProfilesStore()
watch(() => familyStore.currentFamilyId, () => {
  chat.resetAllClean(true)
  summary.$reset()
  templates.$reset()
  profiles.$reset()
}, { flush: 'sync' })
</script>

<style scoped>
.app-layout {
  height: 100vh;
}
.app-header-wrap {
  padding: 0;
  height: 64px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
}
.app-aside-wrap {
  background: #fff;
  border-right: 1px solid #ebeef5;
  overflow: hidden;
}
.app-main-wrap {
  background: #f5f7fa;
  padding: 20px;
  overflow: auto;
}
/* D4: 移动端主内容底部预留 BottomTabBar 高度（60px）+ iOS 安全区 */
.app-main-mobile {
  padding: 12px 12px calc(76px + env(safe-area-inset-bottom, 0px));
}
</style>
