<template>
  <div class="automation-view" aria-live="polite">
    <h1 class="page-title">📈 自动化中心</h1>
    <p class="page-subtitle">查看 AI 总结运行情况和自动化指标</p>

    <!-- 顶部 3 指标卡 -->
    <el-row :gutter="16" class="metrics-row">
      <el-col :xs="24" :md="8">
        <MetricCard title="总结成功率" :value="metrics ? metrics.success_rate_30d : '--'" unit="%" emoji="🎯" :percentStatus="metrics ? metrics.success_rate_30d : null" />
      </el-col>
      <el-col :xs="24" :md="8">
        <MetricCard title="版本新增数" :value="metrics ? metrics.version_count_30d : '--'" unit="个" emoji="📋" />
      </el-col>
      <el-col :xs="24" :md="8">
        <MetricCard title="学习调整" :value="metrics ? metrics.learned_adjustments_30d : '--'" unit="次" emoji="🧠" />
      </el-col>
    </el-row>

    <!-- 手动总结 -->
    <el-card class="section-card">
      <template #header><span class="card-title">⚡ 手动触发总结</span></template>
      <p class="card-desc">OWNER 或 ADULT 可以手动触发今日总结</p>
      <el-button type="primary" size="large" class="big-btn" :loading="summaryStore.triggerPending" @click="triggerSummary">
        📝 生成总结
      </el-button>
    </el-card>

    <!-- 总结日志表格 -->
    <el-card class="section-card">
      <template #header><span class="card-title">📋 总结日志</span></template>
      <el-table :data="summaryStore.list" size="large" empty-text="暂无总结记录" v-loading="summaryStore.loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="run_at" label="运行时间" min-width="180" />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column prop="trigger_source" label="触发来源" width="120" />
        <el-table-column prop="error_message" label="错误信息" min-width="200" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import MetricCard from '@/components/MetricCard.vue'
import { useSummaryStore } from '@/stores/summary'
import { ElMessage } from 'element-plus'

const summaryStore = useSummaryStore()
const metrics = ref(null)

onMounted(async () => {
  const fid = localStorage.getItem('currentFamilyId')
  if (fid) {
    summaryStore.loadMetrics(fid).then(() => { metrics.value = summaryStore.metrics })
    summaryStore.loadList(fid)
  }
})

async function triggerSummary() {
  const fid = localStorage.getItem('currentFamilyId')
  try {
    await summaryStore.trigger(fid)
    ElMessage.success('总结已触发')
    summaryStore.loadList(fid)
  } catch (e) {
    ElMessage.error(e.message || '触发失败')
  }
}
</script>

<style scoped>
.automation-view { padding: 24px 32px; font-family: system-ui, "PingFang SC", "Microsoft YaHei", sans-serif; }
.page-title { font-size: 36px; font-weight: 700; margin: 0 0 8px 0; }
.page-subtitle { font-size: 20px; color: #555; margin: 0 0 24px 0; }
.metrics-row { margin-bottom: 24px; }
.section-card { margin-bottom: 16px; }
.card-title { font-size: 20px; font-weight: 600; }
.card-desc { font-size: 18px; color: #666; margin: 12px 0; }
.big-btn { min-height: 56px; font-size: 20px; }
/* 移动端：外层 AppLayout 已给 12px 内边距，这里归零避免双重挤压 */
@media (max-width: 768px) {
  .automation-view { padding: 0; }
  .page-title { font-size: 26px; }
  .page-subtitle { font-size: 16px; margin-bottom: 16px; }
  .metrics-row { margin-bottom: 16px; }
  .metrics-row :deep(.el-col) { margin-bottom: 12px; }
  .metrics-row :deep(.el-col:last-child) { margin-bottom: 0; }
}
</style>
