<template>
  <el-menu :default-active="activeMenu" class="app-sider" router>
    <el-menu-item index="/dashboard">
      <span class="menu-icon">🏠</span>
      <span class="menu-text">首页</span>
    </el-menu-item>
    <el-menu-item index="/tasks">
      <span class="menu-icon">📝</span>
      <span class="menu-text">我的任务</span>
    </el-menu-item>
    <el-menu-item index="/daily">
      <span class="menu-icon">☀️</span>
      <span class="menu-text">我的一天</span>
    </el-menu-item>
    <!-- Change 13：日程菜单项（点按区域与既有项一致，复用 el-menu-item 54px 高度样式） -->
    <el-menu-item index="/calendar">
      <span class="menu-icon">📅</span>
      <span class="menu-text">日程</span>
    </el-menu-item>
    <el-menu-item index="/chat">
      <span class="menu-icon">🤖</span>
      <span class="menu-text">AI 拆解</span>
    </el-menu-item>
    <el-menu-item index="/work-tasks">
      <span class="menu-icon">💼</span>
      <span class="menu-text">工作任务</span>
    </el-menu-item>
    <el-menu-item index="/templates">
      <span class="menu-icon">📋</span>
      <span class="menu-text">模板管理</span>
    </el-menu-item>
    <el-menu-item index="/automation">
      <span class="menu-icon">📈</span>
      <span class="menu-text">自动化</span>
    </el-menu-item>
    <el-menu-item index="/categories">
      <span class="menu-icon">🗂️</span>
      <span class="menu-text">分类管理</span>
    </el-menu-item>
    <el-menu-item index="/families">
      <span class="menu-icon">🏠</span>
      <span class="menu-text">家庭管理</span>
    </el-menu-item>
  </el-menu>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const activeMenu = computed(() => route.path)
</script>

<style scoped>
.app-sider {
  height: 100%;
  border-right: none;
}
.app-sider .el-menu-item {
  font-size: 17px;
  height: 54px;
  line-height: 54px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.menu-icon {
  font-size: 20px;
}
.menu-text {
  font-size: 17px;
}
</style>
