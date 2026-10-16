<template>
  <el-card class="metric-card" shadow="hover" :class="cardClass">
    <div class="card-inner">
      <div class="title-row">
        <span class="emoji">{{ emoji || '📊' }}</span>
        <span class="title">{{ title }}</span>
      </div>
      <div class="value-row" :aria-live="'polite'">
        <span class="value">{{ formattedValue }}</span>
        <span v-if="unit" class="unit">{{ unitDisplay }}</span>
      </div>
      <div v-if="delta != null" class="delta-row">
        <span class="delta">较上周期 {{ deltaText }}</span>
      </div>
    </div>
  </el-card>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  title: { type: String, required: true },
  value: { type: [Number, String], required: true },
  unit: { type: String, default: '' },
  percentStatus: { type: Number, default: null },
  emoji: { type: String, default: '📊' },
  delta: { type: [Number, String], default: null }
})

const isPercentUnit = props.unit === '%'
const formattedValue = computed(() => {
  const v = props.value
  if (isPercentUnit) {
    const n = Number(v)
    // 指标未就绪时页面传的是占位串 '--'，Math.round(Number('--')) 会渲染成 NaN%，
    // 给老人一块坏掉的屏幕；非有限数一律原样显示占位（终审挂账）
    return Number.isFinite(n) ? Math.round(n) : v
  }
  return v
})
const unitDisplay = computed(() => {
  if (isPercentUnit) return '%'
  return props.unit || ''
})
const deltaText = computed(() => {
  if (props.delta == null) return ''
  const d = props.delta
  if (typeof d === 'number') return d >= 0 ? '+' + d : String(d)
  return String(d)
})
const cardClass = computed(() => {
  if (props.percentStatus == null) return 'bg-default'
  const p = Number(props.percentStatus)
  if (p < 50) return 'bg-red-light'
  if (p < 75) return 'bg-yellow-light'
  return 'bg-green-light'
})
</script>

<style scoped>
.metric-card { border-radius: 16px; min-height: 140px; font-family: system-ui, "PingFang SC", "Microsoft YaHei", sans-serif; }
.card-inner { padding: 4px 8px; }
.title-row { display: flex; align-items: center; gap: 8px; font-size: 20px; font-weight: 600; color: #2b2b2b; margin-bottom: 12px; }
.emoji { font-size: 28px; line-height: 1; }
.title { font-size: 20px; }
.value-row { display: flex; align-items: baseline; gap: 4px; margin-bottom: 8px; }
.value { font-size: 44px; font-weight: 700; line-height: 1.1; color: #1a1a1a; }
.unit { font-size: 20px; color: #555; font-weight: 600; }
.delta-row { margin-top: 4px; }
.delta { font-size: 16px; color: #666; }
.bg-default { background: linear-gradient(135deg, #eef3ff 0%, #f7f9ff 100%); border: 2px solid #bcd0ff; }
.bg-red-light { background: linear-gradient(135deg, #ffe7e7 0%, #fff3f3 100%); border: 2px solid #ffb3b3; }
.bg-yellow-light { background: linear-gradient(135deg, #fff5d6 0%, #fffbea 100%); border: 2px solid #ffd966; }
.bg-green-light { background: linear-gradient(135deg, #e2f7e2 0%, #f1fbf1 100%); border: 2px solid #8cd98c; }
</style>