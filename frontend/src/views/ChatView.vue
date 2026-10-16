<template>
  <div class="chat-view">
    <h1 class="page-title">🤖 AI 任务拆解</h1>

    <!-- Stepper 进度栏 -->
    <el-steps :active="stepActive" align-center class="stepper">
      <el-step title="📝 输入" description="填写任务" />
      <el-step title="🔍 分类" description="AI 识别类型" />
      <el-step title="💭 推理" description="AI 分析拆解" />
      <el-step title="✅ 提案" description="确认拆解方案" />
    </el-steps>

    <!-- 输入区 -->
    <el-card v-if="decompose.isIdle" class="section-card">
      <el-form label-width="100px">
        <el-form-item label="任务 ID">
          <el-input v-model="taskId" placeholder="输入要拆解的任务 ID" size="large" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="large" class="big-btn" @click="startDecompose">
            🚀 开始拆解
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- SSE 实时消息区 -->
    <el-card v-if="!decompose.isIdle" class="section-card sse-area" aria-live="polite">
      <template #header><span class="card-title">💬 AI 回复</span></template>
      <div v-if="decompose.classification" class="msg-block">
        <strong>🔍 分类结果：</strong> {{ decompose.classification }}
      </div>
      <div v-if="decompose.reasoning" class="msg-block">
        <strong>💭 推理过程：</strong>
        <pre class="reasoning-text">{{ decompose.reasoning }}</pre>
      </div>
      <div v-if="decompose.proposal" class="msg-block">
        <strong>✅ 拆解提案：</strong> {{ decompose.proposal }}
      </div>
      <div v-if="decompose.error" class="msg-block error-block">
        <strong>❌ 错误：</strong> {{ decompose.error }}
      </div>
      <div v-if="decompose.isRunning" class="loading-block">
        <el-icon class="is-loading"><Loading /></el-icon> AI 正在思考...
      </div>
    </el-card>

    <!-- 操作按钮 -->
    <div v-if="decompose.isRunning" class="action-row">
      <el-button type="danger" size="large" class="big-btn" @click="cancelDecompose">
        ❌ 取消拆解
      </el-button>
    </div>
    <div v-if="decompose.isConfirmed" class="action-row">
      <el-button type="success" size="large" class="big-btn" @click="decompose.reset()">
        ✅ 完成，返回
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useDecomposeSseStore } from '@/stores/decomposeSse'
import { Loading } from '@element-plus/icons-vue'

const decompose = useDecomposeSseStore()
const taskId = ref('')

const stepActive = computed(() => {
  switch (decompose.status) {
    case 'idle': return 0
    case 'classifying': return 1
    case 'reasoning': return 2
    case 'proposing': return 3
    case 'confirmed': return 4
    default: return 0
  }
})

function startDecompose() {
  const fid = localStorage.getItem('currentFamilyId')
  decompose.start({ task_id: Number(taskId.value), family_id: Number(fid) })
}

function cancelDecompose() {
  const fid = localStorage.getItem('currentFamilyId')
  decompose.cancel(fid, taskId.value)
}
</script>

<style scoped>
.chat-view { padding: 24px 32px; font-family: system-ui, "PingFang SC", "Microsoft YaHei", sans-serif; }
.page-title { font-size: 36px; font-weight: 700; margin: 0 0 24px 0; }
.stepper { margin-bottom: 32px; }
.section-card { margin-bottom: 16px; }
.card-title { font-size: 20px; font-weight: 600; }
.sse-area { min-height: 200px; }
.msg-block { font-size: 18px; margin: 12px 0; line-height: 1.6; }
.reasoning-text { white-space: pre-wrap; font-size: 16px; background: #f5f5f5; padding: 12px; border-radius: 8px; }
.error-block { color: #f56c6c; }
.loading-block { font-size: 20px; color: #409eff; }
.action-row { text-align: center; margin: 24px 0; }
.big-btn { min-height: 56px; font-size: 20px; }
</style>
