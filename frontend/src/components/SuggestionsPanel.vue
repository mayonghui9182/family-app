<template>
  <el-card class="suggestions-panel" shadow="never" v-loading="loading">
    <template #header>
      <div class="panel-head">
        <span class="head-emoji">💡</span>
        <span class="head-title">智能建议</span>
        <span class="head-hint">（根据已选人员档案 + 分类，给您最顺手的推荐）</span>
      </div>
    </template>

    <el-tabs v-model="activeTab" size="large" class="sugs-tabs">
      <!-- Tab 1: 👍 相似已办 -->
      <el-tab-pane label="👍 同类型已完成" name="completed">
        <el-empty
          v-if="!similarCompletedTasks.length"
          description="还没有相似的历史任务"
          :image-size="80"
        />
        <div v-else class="cards-grid">
          <el-card
            v-for="t in similarCompletedTasks.slice(0,6)"
            :key="'c'+t.id"
            class="sug-card"
            shadow="hover"
          >
            <div class="sug-title">{{ t.title }}</div>
            <div class="sug-meta">
              <span v-if="t.dueDate" class="meta-item">📅 {{ t.dueDate }}</span>
              <span v-if="t.completedAt" class="meta-item">✅ 已完成于 {{ fmt(t.completedAt) }}</span>
            </div>
            <div v-if="t.description" class="sug-desc">{{ t.description }}</div>
            <div class="sug-actions">
              <el-button size="large" type="primary" @click="$emit('selectCompletedFill', t)">
                ✍️ 套用这份
              </el-button>
            </div>
          </el-card>
        </div>
      </el-tab-pane>

      <!-- Tab 2: 📋 模板推荐 -->
      <el-tab-pane label="📋 同类型模板" name="templates">
        <el-empty
          v-if="!similarTemplates.length"
          description="还没有匹配的模板"
          :image-size="80"
        />
        <div v-else class="cards-grid">
          <el-card
            v-for="tpl in similarTemplates.slice(0,6)"
            :key="'t'+(tpl.id||tpl.name)"
            class="sug-card"
            shadow="hover"
          >
            <div class="sug-title">{{ tpl.name || tpl.title }}</div>
            <div class="sug-meta">
              <span v-if="tpl.profileTypes && tpl.profileTypes.length" class="meta-item">
                🎯 适用类型：{{ tpl.profileTypes.map(typeText).join('、') }}
              </span>
            </div>
            <div v-if="tpl.description || tpl.triggerSummary" class="sug-desc">
              {{ tpl.description || tpl.triggerSummary }}
            </div>
            <div class="sug-actions">
              <el-button size="large" type="primary" @click="$emit('jumpTemplate', tpl)">
                📋 跳到模板页
              </el-button>
            </div>
          </el-card>
        </div>
      </el-tab-pane>

      <!-- Tab 3: ⏰ 该成员待办 -->
      <el-tab-pane label="⏰ 该成员未完成" name="pending">
        <el-empty
          v-if="!pendingTasks.length"
          description="这位成员目前没有待办任务 🎉"
          :image-size="80"
        />
        <div v-else class="cards-grid">
          <el-card
            v-for="t in pendingTasks.slice(0,8)"
            :key="'p'+t.id"
            class="sug-card"
            :class="['status-' + String(t.status||'').toLowerCase()]"
            shadow="hover"
          >
            <div class="sug-header">
              <span class="sug-title">{{ t.title }}</span>
              <el-tag :type="statusTag(t.status)" size="large">{{ statusLabel(t.status) }}</el-tag>
            </div>
            <div class="sug-meta">
              <span v-if="t.dueDate" class="meta-item">📅 截止 {{ t.dueDate }}</span>
              <span v-if="t.priority" class="meta-item">优先级：{{ priorityLabel(t.priority) }}</span>
            </div>
            <div v-if="t.description" class="sug-desc">{{ t.description }}</div>
            <div class="sug-actions">
              <el-button size="large" type="primary" @click="$emit('viewPending', t)">
                🧭 去处理它
              </el-button>
            </div>
          </el-card>
        </div>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup>
import { ref } from 'vue';
import { profileTypeName } from '../utils/profileRender.js';

defineProps({
  similarCompletedTasks: { type: Array, default: () => [] },   // 父 TaskView 调 S-1 后下发
  similarTemplates: { type: Array, default: () => [] },
  pendingTasks: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
});
defineEmits(['selectCompletedFill', 'jumpTemplate', 'viewPending']);

const activeTab = ref('completed');

/* ===== 展示小工具 ===== */
function typeText(t) { return profileTypeName(t); }
function statusLabel(s) {
  return ({ DRAFT:'草稿', PENDING:'待办', IN_PROGRESS:'进行中', COMPLETED:'已完成', CANCELLED:'已取消' })[s] || (s || '');
}
function statusTag(s) {
  return ({ DRAFT:'info', PENDING:'warning', IN_PROGRESS:'primary', COMPLETED:'success', CANCELLED:'danger' })[s] || 'info';
}
function priorityLabel(p) {
  return ({ LOW:'低', MEDIUM:'中', HIGH:'高' })[p] || '中';
}
function fmt(iso) {
  if (!iso) return '';
  try { return new Date(iso).toLocaleDateString('zh-CN'); } catch { return iso; }
}
</script>

<style scoped>
.suggestions-panel { margin: 16px 0 0; }
.panel-head { display: flex; align-items: center; gap: 10px; }
.head-emoji { font-size: 22px; }
.head-title { font-size: 20px; font-weight: 800; color: #0f172a; }
.head-hint { color: #64748b; font-size: 14px; margin-left: 6px; }

:deep(.sugs-tabs .el-tabs__item) {
  height: 50px; line-height: 50px;
  font-size: 17px;
}

.cards-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
  padding: 4px 2px;
}
.sug-card { min-height: 150px; display: flex; flex-direction: column; }
.sug-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 8px; margin-bottom: 6px; }
.sug-title { font-size: 18px; font-weight: 700; color: #111827; line-height: 1.35; }
.sug-meta { margin: 6px 0 4px; font-size: 14px; color: #475569; display: flex; flex-wrap: wrap; gap: 10px; }
.meta-item { white-space: nowrap; }
.sug-desc { font-size: 14.5px; color: #334155; line-height: 1.55; }
.sug-actions { margin-top: auto; padding-top: 10px; }
.sug-actions .el-button { min-height: 44px; font-size: 15.5px; }

/* 待办状态颜色 */
.sug-card.status-pending { border-left: 5px solid #f59e0b; }
.sug-card.status-in_progress { border-left: 5px solid #2563eb; }
.sug-card.status-draft { border-left: 5px solid #a3a3a3; }
</style>
