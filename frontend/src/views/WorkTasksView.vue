<template>
  <div class="work-tasks-page">
    <!-- ===== 顶部导航栏 ===== -->
    <div class="wt-nav">
      <el-button size="large" @click="goBack">🔙 返回</el-button>
      <h2 class="wt-nav-title">
        <span class="wt-nav-emoji">💼</span>
        工作任务
      </h2>
      <div style="min-width: 130px;"></div>
    </div>

    <!-- ===== 消息列表区 ===== -->
    <div class="wt-messages" ref="messagesRef">
      <!-- 空态欢迎 -->
      <div v-if="messages.length === 0" class="wt-welcome">
        <div class="wt-welcome-icon">💼</div>
        <p class="wt-welcome-text">告诉我你想安排什么工作</p>
        <p class="wt-welcome-hint">例如：准备下周的项目汇报</p>
      </div>

      <!-- 消息气泡 -->
      <div
        v-for="msg in messages"
        :key="msg.id"
        :class="['wt-msg', msg.role]"
      >
        <!-- 用户消息：右对齐蓝色气泡 -->
        <div v-if="msg.role === 'user'" class="wt-bubble user-bubble">
          {{ msg.content }}
        </div>

        <!-- AI 消息：左对齐灰色气泡，结构化内容 -->
        <div v-else class="wt-bubble ai-bubble">
          <!-- 分类标签 -->
          <div v-if="msg.classification" class="wt-classify-tag">
            📂 分类：{{ msg.classification.category }}
            <span class="wt-confidence">（{{ Math.round(msg.classification.confidence * 100) }}%）</span>
          </div>

          <!-- 思考过程（逐字流式追加，可折叠） -->
          <details v-if="msg.reasoning" class="wt-reasoning" open>
            <summary>🤔 思考过程</summary>
            <p class="wt-reasoning-text">{{ msg.reasoning }}</p>
          </details>

          <!-- 提案卡片 -->
          <div v-if="msg.proposal" class="wt-proposal">
            <h4 class="wt-proposal-title">📋 {{ msg.proposal.task_title }}</h4>
            <ul class="wt-subtask-list">
              <li v-for="(s, i) in msg.proposal.subtasks" :key="i">{{ s }}</li>
            </ul>
          </div>

          <!-- 完成标记 -->
          <div v-if="msg.done" class="wt-done">✅ 已创建草稿，可前往确认</div>

          <!-- 错误提示 -->
          <div v-if="msg.error" class="wt-error">❌ {{ msg.error }}</div>
        </div>
      </div>

      <!-- 流式加载中提示 -->
      <div v-if="streaming" class="wt-msg ai">
        <div class="wt-bubble ai-bubble wt-typing">
          <span class="wt-typing-dots">AI 正在思考</span>
        </div>
      </div>
    </div>

    <!-- ===== 底部输入区 ===== -->
    <div class="wt-input-bar">
      <el-input
        v-model="inputText"
        type="textarea"
        :rows="2"
        resize="none"
        placeholder="说说你想安排什么工作…"
        @keydown.enter.ctrl="send"
        class="wt-input"
      />
      <el-button
        v-if="!streaming"
        type="primary"
        size="large"
        @click="send"
        :disabled="!inputText.trim()"
        class="wt-send-btn"
      >
        发送
      </el-button>
      <el-button
        v-else
        type="danger"
        size="large"
        @click="stop"
        class="wt-send-btn"
      >
        停止
      </el-button>
    </div>
  </div>
</template>

<script setup>
/**
 * WorkTasksView.vue — 工作任务 AI 对话页（DeepSeek 式）
 *
 * 路由：/work-tasks（AppLayout 子路由，requiresAuth: true）
 * 数据流：用户输入 → POST /api/work-tasks/chat → SSE 流式返回
 *   classification → reasoning×N → proposal → done
 * 借鉴 DecomposeChatPageWrapper.vue 的导航栏范式 + DeepSeek 网页版的对话布局。
 */
import { ref, nextTick, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { streamWorkChat } from '../api/workTasks'

const router = useRouter()

// ===== 消息列表 =====
const messages = ref([])
const inputText = ref('')
const streaming = ref(false)
const messagesRef = ref(null)
let controller = null
let msgIdCounter = 0

// ===== 发送消息 =====
function send() {
  const text = inputText.value.trim()
  if (!text || streaming.value) return

  // 追加用户消息
  const userMsg = { id: ++msgIdCounter, role: 'user', content: text }
  messages.value.push(userMsg)
  inputText.value = ''
  streaming.value = true

  // 创建 AI 消息占位（流式追加内容）
  const aiMsg = ref({
    id: ++msgIdCounter,
    role: 'ai',
    classification: null,
    reasoning: '',
    proposal: null,
    done: null,
    error: null
  })
  messages.value.push(aiMsg.value)

  // 滚动到底部
  scrollToBottom()

  // 启动 SSE 流
  controller = streamWorkChat(text, {
    onClassification(data) {
      aiMsg.value.classification = data
      scrollToBottom()
    },
    onReasoning(chunk) {
      aiMsg.value.reasoning += chunk
      scrollToBottom()
    },
    onProposal(data) {
      aiMsg.value.proposal = data
      scrollToBottom()
    },
    onDone(data) {
      aiMsg.value.done = data
      streaming.value = false
      controller = null
      scrollToBottom()
    },
    onError(msg) {
      aiMsg.value.error = msg
      streaming.value = false
      controller = null
      scrollToBottom()
    }
  })
}

// ===== 停止流 =====
function stop() {
  if (controller) {
    controller.cancel()
    controller = null
  }
  streaming.value = false
}

// ===== 返回上一页 =====
function goBack() {
  router.back()
}

// ===== 滚动到底部 =====
function scrollToBottom() {
  nextTick(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  })
}

// ===== 组件卸载时取消流 =====
onUnmounted(() => {
  if (controller) controller.cancel()
})
</script>

<style scoped>
.work-tasks-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #f5f7fa;
}

/* ===== 顶部导航栏 ===== */
.wt-nav {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 32px;
  background: linear-gradient(135deg, #eef2ff, #e0e7ff);
  border-bottom: 1px solid #d1d5db;
}
.wt-nav-title {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  color: #1e1b4b;
  display: flex;
  align-items: center;
  gap: 10px;
}
.wt-nav-emoji {
  font-size: 32px;
}

/* ===== 消息列表区 ===== */
.wt-messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px 32px;
  max-width: 900px;
  margin: 0 auto;
  width: 100%;
}

/* ===== 空态欢迎 ===== */
.wt-welcome {
  text-align: center;
  padding: 80px 20px;
}
.wt-welcome-icon {
  font-size: 64px;
  margin-bottom: 16px;
}
.wt-welcome-text {
  font-size: 22px;
  color: #1e1b4b;
  font-weight: 600;
  margin-bottom: 8px;
}
.wt-welcome-hint {
  font-size: 17px;
  color: #6b7280;
}

/* ===== 消息气泡 ===== */
.wt-msg {
  margin-bottom: 20px;
  display: flex;
}
.wt-msg.user {
  justify-content: flex-end;
}
.wt-msg.ai {
  justify-content: flex-start;
}
.wt-bubble {
  max-width: 75%;
  padding: 14px 18px;
  border-radius: 16px;
  font-size: 17px;
  line-height: 1.6;
  word-break: break-word;
}
.user-bubble {
  background: #2563eb;
  color: #fff;
  border-bottom-right-radius: 4px;
}
.ai-bubble {
  background: #fff;
  color: #1f2937;
  border: 1px solid #e5e7eb;
  border-bottom-left-radius: 4px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}

/* ===== 分类标签 ===== */
.wt-classify-tag {
  font-size: 15px;
  color: #6366f1;
  margin-bottom: 8px;
  padding: 4px 10px;
  background: #eef2ff;
  border-radius: 8px;
  display: inline-block;
}
.wt-confidence {
  color: #9ca3af;
  font-size: 14px;
}

/* ===== 思考过程折叠区 ===== */
.wt-reasoning {
  margin: 10px 0;
  padding: 8px 12px;
  background: #f9fafb;
  border-radius: 8px;
  border: 1px solid #f3f4f6;
}
.wt-reasoning summary {
  cursor: pointer;
  font-size: 15px;
  color: #6b7280;
  font-weight: 600;
}
.wt-reasoning-text {
  margin: 8px 0 0;
  font-size: 15px;
  color: #4b5563;
  line-height: 1.6;
  white-space: pre-wrap;
}

/* ===== 提案卡片 ===== */
.wt-proposal {
  margin-top: 12px;
  padding: 12px 16px;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  border-radius: 12px;
}
.wt-proposal-title {
  margin: 0 0 8px;
  font-size: 18px;
  color: #166534;
  font-weight: 700;
}
.wt-subtask-list {
  margin: 0;
  padding-left: 20px;
}
.wt-subtask-list li {
  font-size: 16px;
  color: #15803d;
  line-height: 1.8;
}

/* ===== 完成标记 ===== */
.wt-done {
  margin-top: 10px;
  font-size: 15px;
  color: #16a34a;
  font-weight: 600;
}

/* ===== 错误提示 ===== */
.wt-error {
  margin-top: 10px;
  font-size: 15px;
  color: #dc2626;
  font-weight: 600;
}

/* ===== 流式加载中 ===== */
.wt-typing {
  color: #9ca3af;
  font-size: 16px;
}
.wt-typing-dots::after {
  content: '…';
  animation: wt-dots 1.5s infinite;
}
@keyframes wt-dots {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

/* ===== 底部输入区 ===== */
.wt-input-bar {
  display: flex;
  gap: 12px;
  padding: 16px 32px;
  background: #fff;
  border-top: 1px solid #e5e7eb;
  max-width: 900px;
  margin: 0 auto;
  width: 100%;
}
.wt-input {
  flex: 1;
}
.wt-input :deep(.el-textarea__inner) {
  font-size: 18px;
  line-height: 1.6;
}
.wt-send-btn {
  flex-shrink: 0;
  min-width: 100px;
  font-size: 18px;
}

/* ===== 移动端适配 ===== */
@media (max-width: 768px) {
  .wt-nav {
    flex-direction: column;
    gap: 12px;
    padding: 12px 16px;
  }
  .wt-nav-title {
    font-size: 20px;
  }
  .wt-nav-emoji {
    font-size: 26px;
  }
  .wt-messages {
    padding: 16px 12px;
  }
  .wt-bubble {
    max-width: 90%;
    font-size: 16px;
  }
  .wt-input-bar {
    padding: 12px 16px;
    flex-direction: column;
  }
  .wt-send-btn {
    width: 100%;
  }
}
</style>
