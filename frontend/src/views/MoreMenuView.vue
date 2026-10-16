<template>
  <div class="more-menu-view">
    <h2 class="section-title">更多功能</h2>
    <p class="more-subtitle">配置与管理类功能都在这里</p>
    <div class="more-grid">
      <RouterLink
        v-for="item in entries"
        :key="item.path"
        :to="item.path"
        class="more-card"
        :aria-label="item.label"
      >
        <span class="more-icon" aria-hidden="true">{{ item.icon }}</span>
        <span class="more-label">{{ item.label }}</span>
      </RouterLink>
    </div>
  </div>
</template>

<script setup>
// D2: 低频配置入口聚合页（模板管理/自动化/分类管理/家庭管理/日程/密码重置）
// emoji + 中文文案与 AppSider 一致
// 改用 RouterLink 替代 button+router.push：原生 a 标签语义，点击事件更稳定，
// 同时支持浏览器右键"在新标签页打开"、中键点击等标准行为
const entries = [
  { path: '/daily', icon: '☀️', label: '我的一天' },
  { path: '/chat', icon: '🤖', label: '帮我安排步骤' },
  { path: '/profiles', icon: '👥', label: '家人档案' },
  { path: '/templates', icon: '📋', label: '模板管理' },
  { path: '/automation', icon: '📈', label: '自动化' },
  { path: '/categories', icon: '🗂️', label: '分类管理' },
  { path: '/families', icon: '🏠', label: '家庭管理' },
  // Change 13：日程入口（文案朴素两个汉字，老人一眼能懂）
  { path: '/calendar', icon: '📅', label: '日程' },
  // 密码重置：用户主动要求新增（老人友好，独立入口）
  { path: '/password', icon: '🔐', label: '修改密码' }
]
</script>

<style scoped>
.more-menu-view {
  max-width: 640px;
  margin: 0 auto;
}
.more-subtitle {
  font-size: 0.95rem;
  color: #909399;
  margin: 4px 0 20px;
}
.more-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}
.more-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 110px;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 12px;
  cursor: pointer;
  transition: box-shadow 0.15s ease;
  text-decoration: none;
  color: inherit;
}
.more-card:hover {
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.12);
}
.more-icon {
  font-size: 34px;
  line-height: 1;
}
.more-label {
  font-size: 17px;
  font-weight: 600;
  color: #1f2937;
}
</style>
