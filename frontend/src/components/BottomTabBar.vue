<template>
  <nav class="bottom-tab-bar" aria-label="移动端底部导航">
    <RouterLink
      v-for="tab in tabs"
      :key="tab.path"
      :to="tab.path"
      class="tab-item"
      :class="{ active: isActive(tab.path) }"
      :aria-current="isActive(tab.path) ? 'page' : undefined"
    >
      <span class="tab-icon" aria-hidden="true">{{ tab.icon }}</span>
      <span class="tab-label">{{ tab.label }}</span>
    </RouterLink>
  </nav>
</template>

<script setup>
// D2: 底部导航 = 高频 3 项（首页/我的任务/AI拆解）+ 更多聚合页入口
// 文案与 AppSider 保持一致，降低老人学习成本
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const currentPath = computed(() => route.path)

const tabs = [
  { path: '/dashboard', icon: '🏠', label: '首页' },
  { path: '/tasks', icon: '📝', label: '我的任务' },
  { path: '/calendar', icon: '📅', label: '日程' },
  { path: '/more', icon: '⋯', label: '更多' }
]

function isActive(path) {
  return currentPath.value === path || currentPath.value.startsWith(path + '/')
}
</script>

<style scoped>
.bottom-tab-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 2000;
  display: flex;
  height: 60px;
  background: #fff;
  border-top: 1px solid #ebeef5;
  /* D4: iOS 微信 WebView 底部安全区 */
  padding-bottom: env(safe-area-inset-bottom, 0px);
}
.tab-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  min-height: 44px;
  text-decoration: none;
  color: #606266;
  font-size: 1rem;
}
/* 保持导航名称完整，长名称由弹性布局分配宽度，不在固定高度中折行。 */
.tab-label {
  white-space: nowrap;
  line-height: 1.3;
}
.tab-icon {
  font-size: 22px;
  line-height: 1;
}
.tab-item.active {
  color: #2563eb;
  font-weight: 700;
}
</style>
