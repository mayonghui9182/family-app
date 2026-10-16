<template>
  <div class="decompose-chat-panel" role="region" aria-label="AI 拆解沟通面板">
    <!-- ===== 顶部标题栏（所有态显示） ===== -->
    <div class="dcp-header">
      <div class="dcp-title">
        <span class="dcp-title-emoji">{{ isInterview ? '🧠' : '🤖' }}</span>
        <h3 class="dcp-title-text">{{ isInterview ? 'AI 完善档案访谈' : 'AI 任务拆解' }}</h3>
        <el-tag size="large" :type="statusTagType" effect="dark" class="dcp-status-tag">
          {{ statusLabel }}
        </el-tag>
      </div>
      <div class="dcp-header-actions">
        <el-button
          v-if="isInProgress || isIdle"
          size="large"
          plain
          @click="resetAllClean(false)"
          title="清空当前会话重新开始"
        >
          🔄 重置
        </el-button>
        <el-button size="large" type="danger" plain @click="handleCloseBtn">
          ❌ 关闭
        </el-button>
      </div>
    </div>

    <!-- ===== 面板主体（6 态 6 视图） ===== -->
    <div class="dcp-body">

      <!-- ========== 态 1：idle（欢迎空态 + 输入框 + 两大主入口按钮） ========== -->
      <div v-if="isIdle" class="dcp-idle">
        <div class="dcp-idle-emoji">{{ isInterview ? '🧠💬' : '🤖✨' }}</div>
        <p v-if="isInterview" class="dcp-idle-hint">
          AI 会像聊天一样问几个简单问题，把这位家庭成员的档案补充完整<br/>
          <span style="font-size: 16px; color: #909399;">
            照着问题回答就行，随时可以点「保存档案更新」结束
          </span>
        </p>
        <p v-else class="dcp-idle-hint">
          告诉我想做的事，一起安排成容易完成的小步骤<br/>
          <span style="font-size: 16px; color: #909399;">
            想快速了解？点「▶️ 一键演示」看完整流程（宝宝辅食计划）
          </span>
        </p>

        <!-- 直接输入任务描述开始沟通（访谈模式不显示：进入即自动开始访谈） -->
        <div v-if="!isInterview" class="dcp-idle-input-wrap">
          <el-input
            v-model="idleInputText"
            size="large"
            type="textarea"
            :rows="3"
            class="dcp-idle-input"
            placeholder="直接告诉我你想拆解什么任务吧～ 例如：宝宝 6 个月了，帮我制定辅食添加计划"
            :disabled="idleLoading"
          />
          <div class="dcp-idle-input-actions">
            <el-button
              size="large"
              type="primary"
              class="dcp-btn-big"
              :disabled="!idleInputText.trim() || idleLoading"
              :loading="idleLoading"
              @click="startFromIdleInput"
            >
              🚀 开始拆解
            </el-button>
          </div>
        </div>

        <div class="dcp-idle-actions">
          <!-- 访谈模式：只留一个「开始访谈」按钮（onMounted 自动触发，失败时可手动重试） -->
          <el-button
            v-if="isInterview"
            size="large"
            type="primary"
            class="dcp-btn-big"
            :loading="idleLoading"
            :disabled="idleLoading"
            @click="startInterview"
          >
            🚀 {{ idleLoading ? '访谈正在开始…' : '开始 AI 访谈' }}
          </el-button>
          <template v-else>
            <el-button size="large" type="warning" class="dcp-btn-big" data-ai="true" @click="runDemo">
              试试示例：宝宝辅食计划
            </el-button>
            <el-button
              size="large"
              type="primary"
              plain
              class="dcp-btn-big"
              :loading="idleLoading"
              :disabled="idleLoading"
              @click="startChattingFromForm"
            >
              💬 帮我安排这件事
            </el-button>
          </template>
        </div>
        <div v-if="initForm && initForm.title" class="dcp-idle-initform">
          <el-alert type="info" show-icon :closable="false">
            <template #title>
              <div style="font-size: 16px;">
                {{ isInterview ? '🧠 本次访谈对象：' : '📌 当前创建表单上下文：' }}
                <strong>{{ initForm.title }}</strong>
                <span v-if="initForm.description"> · {{ initForm.description }}</span>
              </div>
            </template>
          </el-alert>
        </div>
      </div>

      <!-- ========== 态 2：chatting（消息区 + typing 动效 + 底部输入行） ========== -->
      <div v-else-if="isChatting" class="dcp-chatting">
        <!-- 消息列表（含 typing 动效） -->
        <div class="dcp-messages" role="log" aria-label="AI 沟通消息记录">
          <div
            v-for="(m, i) in messages"
            :key="i"
            class="dcp-msg-row"
            :class="m.role === 'user' ? 'dcp-msg-user' : 'dcp-msg-ai'"
          >
            <div class="dcp-msg-avatar">
              <span style="font-size: 28px;">{{ m.role === 'user' ? '👤' : '🤖' }}</span>
            </div>
            <div class="dcp-msg-bubble">
              <div class="dcp-msg-role">{{ m.role === 'user' ? '我说' : 'AI 说' }}</div>
              <div class="dcp-msg-content">{{ m.content }}</div>
            </div>
          </div>

          <!-- typing：AI 正在思考大动效（老人一眼感知） -->
          <div v-if="typing" class="dcp-msg-row dcp-msg-ai dcp-typing-row" aria-live="polite">
            <div class="dcp-msg-avatar"><span style="font-size: 28px;">🤖</span></div>
            <div class="dcp-msg-bubble dcp-typing-bubble">
              <div class="dcp-msg-role">AI 正在思考…</div>
              <div class="dcp-typing-dots">
                <span class="dot d1">●</span><span class="dot d2">●</span><span class="dot d3">●</span>
              </div>
            </div>
          </div>
        </div>

        <!-- patch_preview 提醒卡（有变更时显示） -->
        <div v-if="patchPreview && (patchCount > 0)" class="dcp-patch-preview">
          <el-alert type="info" show-icon :closable="false">
            <template #title>
              <div style="font-size: 16px;">
                📝 AI 建议更新档案：修改 <strong>{{ patchKeyCount }}</strong> 项
                <span v-if="patchRemoveCount > 0">，移除 <strong>{{ patchRemoveCount }}</strong> 项</span>
              </div>
              <div class="dcp-patch-detail">
                <div v-for="(val, k) in (patchPreview.patch || {})" :key="'p-'+k" class="dcp-patch-item">
                  🔹 <b>{{ k }}：</b>{{ formatPatchVal(val) }}
                </div>
                <div v-for="k in (patchPreview.remove_keys || [])" :key="'r-'+k" class="dcp-patch-item dcp-patch-remove">
                  ❌ <b>{{ k }}：</b>从档案中移除
                </div>
              </div>
            </template>
          </el-alert>
        </div>

        <!-- 输入行（老人大尺寸；访谈模式只留输入+发送+结束，拆解模式含草稿/演示按钮） -->
        <div class="dcp-input-row">
          <el-button
            v-if="!isInterview"
            size="large"
            data-ai="true"
            type="success"
            class="dcp-btn-big"
            :disabled="!hasMessages || typing"
            @click="generateDecompose"
          >
            📋 生成拆解草稿
          </el-button>
          <el-button
            v-if="!isInterview"
            size="large"
            data-ai="true"
            type="warning"
            plain
            class="dcp-btn-big"
            :disabled="typing"
            @click="runDemo"
          >
            ▶️ 一键演示
          </el-button>
          <el-input
            v-model="inputText"
            size="large"
            class="dcp-input"
            :placeholder="isInterview
              ? '照实回答 AI 的问题就行…（例如：我平时血压 135/85），按 Enter 发送'
              : '对 AI 说点什么…（例如：宝宝对牛奶过敏），按 Enter 发送'"
            @keyup.enter="handleSend"
            :disabled="typing"
          />
          <el-button
            size="large"
            type="primary"
            class="dcp-btn-big"
            :disabled="!inputText.trim() || typing"
            @click="handleSend"
          >
            ➡️ 发送
          </el-button>
          <el-button size="large" type="danger" plain class="dcp-btn-big" @click="cancelSession">
            {{ isInterview ? '❌ 结束访谈' : '❌ 放弃拆解' }}
          </el-button>
        </div>
      </div>

      <!-- ========== 态 3：proposing（推理区 + 草稿卡 + 4 个终态按钮） ========== -->
      <div v-else-if="isProposing" class="dcp-proposing">
        <!-- 流式推理（逐字追加） -->
        <div v-if="typing || reasoningText" class="dcp-reasoning">
          <div class="dcp-reasoning-title">
            💡 AI 思考中：{{ typing ? '（正在分析…）' : '（已完成分析）' }}
          </div>
          <pre class="dcp-reasoning-text">{{ reasoningText || '（暂无推理过程）' }}</pre>
        </div>

        <!-- 草稿卡片：标题 + 任务列表 + 档案变更预览 -->
        <div v-if="hasProposal" class="dcp-proposal-card">
          <div class="dcp-proposal-head">
            <div class="dcp-proposal-head-left">
              <h4 class="dcp-proposal-title">
                {{ isInterview ? '🧠 本轮访谈小结：' : '📋 草稿预览：' }}<span>{{ proposal.title || (isInterview ? '档案访谈' : '拆解结果') }}</span>
              </h4>
              <div v-if="proposal.description" class="dcp-proposal-desc">{{ proposal.description }}</div>
            </div>
            <el-tag v-if="!isInterview" size="large" type="success" effect="dark">
              共 {{ proposalTaskCount }} 条待办
            </el-tag>
          </div>

          <!-- 任务列表（访谈模式的占位任务不展示，只看档案变更） -->
          <el-table
            v-if="!isInterview"
            :data="proposal.tasks"
            size="large"
            border
            stripe
            class="dcp-proposal-table"
            :row-style="{ fontSize: '16px' }"
          >
            <el-table-column type="index" label="#" width="60" align="center" />
            <el-table-column label="任务标题" min-width="260">
              <template #default="{ row }">
                <div style="font-size: 17px;"><b>{{ row.title }}</b></div>
                <div v-if="row.description" style="font-size: 14px; color: #606266; margin-top: 4px;">
                  {{ row.description }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="优先级" width="110" align="center">
              <template #default="{ row }">
                <el-tag size="large" :type="priorityTagType(row.priority)">
                  {{ priorityLabel(row.priority) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="截止日期" width="160" align="center">
              <template #default="{ row }">{{ row.dueDate || '（不设）' }}</template>
            </el-table-column>
          </el-table>

          <!-- 档案变更 -->
          <div v-if="hasProposalPatch" class="dcp-proposal-patch">
            <h5>📝 档案同步更新内容</h5>
            <div class="dcp-patch-detail">
              <div v-for="(val, k) in (proposal.profilePatch || {})" :key="'pp-'+k" class="dcp-patch-item">
                🔹 <b>{{ k }}：</b>{{ formatPatchVal(val) }}
              </div>
              <div v-for="k in (proposal.remove_keys || [])" :key="'pr-'+k" class="dcp-patch-item dcp-patch-remove">
                ❌ <b>{{ k }}：</b>从档案中移除
              </div>
            </div>
          </div>
        </div>

        <!-- 未显示 proposal 的空态 -->
        <div v-if="!hasProposal && !typing" class="dcp-proposal-empty">
          <el-empty description="草稿生成中，稍等一下哦～" />
        </div>

        <!-- 终态按钮行（只有 hasProposal 时显示，防止误点；访谈模式隐藏「确认生成待办」避免占位任务落库） -->
        <div v-if="hasProposal" class="dcp-final-actions">
          <el-button v-if="!isInterview" size="large" type="success" class="dcp-btn-big" data-ai="true" @click="confirmProposal">
            ✅ 确认生成待办（{{ proposalTaskCount }} 条）
          </el-button>
          <el-button size="large" type="warning" class="dcp-btn-big" @click="saveOnlyProfile">
            {{ isInterview ? '📋 保存档案更新，结束访谈' : '📋 只更新档案，不生成任务' }}
          </el-button>
          <el-button size="large" type="primary" plain class="dcp-btn-big" @click="backToChatting">
            {{ isInterview ? '↩️ 继续访谈' : '↩️ 继续和 AI 沟通' }}
          </el-button>
          <el-button size="large" type="danger" plain class="dcp-btn-big" @click="cancelSession">
            {{ isInterview ? '❌ 放弃本次访谈' : '❌ 放弃本次拆解' }}
          </el-button>
        </div>
      </div>

      <!-- ========== 态 4：confirmed（✅ 生成待办成功） ========== -->
      <el-result
        v-else-if="isConfirmed"
        icon="success"
        title="✅ 拆解完成，待办任务已生成！"
        :sub-title="confirmedSubtitle"
        class="dcp-result"
      >
        <template #extra>
          <el-button size="large" type="primary" class="dcp-btn-big" @click="handleCloseAfterFinal('confirmed')">
            🔙 返回任务列表查看
          </el-button>
          <el-button size="large" class="dcp-btn-big" @click="resetAllClean(false)">
            🔄 再拆一个新任务
          </el-button>
        </template>
      </el-result>

      <!-- ========== 态 5：archived（📋 只更新了档案） ========== -->
      <el-result
        v-else-if="isArchived"
        icon="info"
        :title="isInterview ? '🧠 访谈完成，档案已更新' : '📋 人员档案已更新'"
        :sub-title="isInterview
          ? '这次访谈收集到的信息已经同步到这位家庭成员的档案里了～'
          : '没有生成待办任务，只把本次 AI 建议的变更同步到档案了～'"
        class="dcp-result"
      >
        <template #extra>
          <el-button size="large" type="primary" class="dcp-btn-big" @click="handleCloseAfterFinal('archived')">
            {{ isInterview ? '🔙 返回人员档案' : '🔙 返回任务列表' }}
          </el-button>
          <el-button size="large" class="dcp-btn-big" @click="resetAllClean(false)">
            {{ isInterview ? '🔄 再访谈一位成员' : '🔄 再拆一个新任务' }}
          </el-button>
        </template>
      </el-result>

      <!-- ========== 态 6：cancelled（❌ 已放弃） ========== -->
      <el-result
        v-else-if="isCancelled"
        icon="warning"
        :title="isInterview ? '已结束本次访谈' : '已放弃本次拆解'"
        :sub-title="isInterview
          ? '本次访谈没有保存任何档案变更，一切都没变～'
          : '本次沟通记录和草稿都没有保存，一切都没变～'"
        class="dcp-result"
      >
        <template #extra>
          <el-button size="large" type="primary" class="dcp-btn-big" @click="resetAllClean(false)">
            🔄 重新开始
          </el-button>
          <el-button size="large" class="dcp-btn-big" @click="handleCloseAfterFinal('cancelled')">
            {{ isInterview ? '🔙 返回人员档案' : '🔙 返回任务列表' }}
          </el-button>
        </template>
      </el-result>

    </div><!-- /.dcp-body -->
  </div>
</template>

<script setup>
/**
 * DecomposeChatPanel.vue（T1-10 S5 Step2）
 *
 * 纯前端 Mock SSE 闭环演示面板：
 *   1. 6 态状态机（idle/chatting/proposing/confirmed/archived/cancelled）
 *   2. Mock C 混合模式：默认用户自由输入多轮沟通 + 「▶️ 一键演示」3 轮剧本
 *   3. 老人友好：large 按钮/输入框 + emoji 图标 + 中文提示 + 大字号标题
 *   4. data-ai="true" 按钮：一键演示×2 / 生成拆解草稿 / 确认生成（Con8 全局 CSS 匹配）
 *   5. 5 次二次确认：confirmProposal/saveOnlyProfile/cancelSession/resetAllClean(非silent)/离开(confirmLeave)
 *
 * 对外 API：
 *   Props:
 *     initForm = { title, description, priority, dueDate, categoryId, assigneeProfileId }
 *   Emits:
 *     @update:visible(Boolean)   — v-model:visible 开关
 *     @confirmed(result)         — D-5 写业务成功后 emit（父组件可刷新任务列表）
 *     @archived(result)          — D-6 仅更档案成功后 emit
 *     @cancelled                 — D-7 放弃后 emit
 */
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useDecomposeSseStore } from '../stores/decomposeSse'

const props = defineProps({
  visible: { type: Boolean, default: false },
  initForm: {
    type: Object,
    default: () => null
  }
})
const emit = defineEmits([
  'update:visible', 'confirmed', 'archived', 'cancelled'
])

// ====== Store（Pinia；组件内 use，非顶层调用——Pinia 经验教训 366961 ✅）======
const store = useDecomposeSseStore()

// ====== 访谈模式判定：档案页 deep-link 经 Wrapper pageInitForm.sessionType 传入 ======
// PROFILE_INTERVIEW 时面板呈现访谈式 UI（隐藏拆解/演示入口，进入即自动开始首轮访谈）
const isInterview = computed(() => props.initForm?.sessionType === 'PROFILE_INTERVIEW')

// ====== 本地输入态 ======
const inputText = ref('')
const idleInputText = ref('')  // idle 态直接输入任务描述
const idleLoading = ref(false) // idle 态按钮 loading（init + 首轮 SSE 等待）

// ====== 核心状态计算（全靠 store，本地不再另存 6 态，避免双源分裂）======
const status              = computed(() => store.status)
const isIdle              = computed(() => store.isIdle)
const isChatting          = computed(() => store.isChatting)
const isProposing         = computed(() => store.isProposing)
const isConfirmed         = computed(() => store.isConfirmed)
const isArchived          = computed(() => store.isArchived)
const isCancelled         = computed(() => store.isCancelled)
const isInProgress        = computed(() => store.isInProgress)
const messages            = computed(() => store.messages)
const typing              = computed(() => store.typing)
const hasMessages         = computed(() => store.hasMessages)
const hasProposal         = computed(() => store.hasProposal)
const proposal            = computed(() => store.proposal)
const proposalTaskCount   = computed(() => store.proposalTaskCount)
const reasoningText       = computed(() => store.reasoningText)
const patchPreview        = computed(() => store.patchPreview)
const confirmedResult     = computed(() => store.confirmedResult)

// ====== 派生显示 ======
const STATUS_LABEL = {
  idle:       '等待开始',
  chatting:   '沟通中',
  proposing:  '生成草稿中',
  confirmed:  '已生成待办',
  archived:   '已更新档案',
  cancelled:  '已放弃'
}
const STATUS_TAG = {
  idle:       'info',
  chatting:   'warning',
  proposing:  'warning',
  confirmed:  'success',
  archived:   'primary',
  cancelled:  'danger'
}
const PRIORITY_LABEL = { HIGH: '高', MEDIUM: '中', LOW: '低' }
const PRIORITY_TAG   = { HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' }

const statusLabel   = computed(() => STATUS_LABEL[status.value] || '')
const statusTagType = computed(() => STATUS_TAG[status.value] || 'info')
function priorityLabel(p) { return PRIORITY_LABEL[p] || '中' }
function priorityTagType(p) { return PRIORITY_TAG[p] || 'warning' }
function formatPatchVal(v) {
  if (v === null || v === undefined) return '（空）'
  if (Array.isArray(v)) return v.join('、')
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}
const patchKeyCount = computed(() => {
  if (!patchPreview.value || !patchPreview.value.patch) return 0
  return Object.keys(patchPreview.value.patch).length
})
const patchRemoveCount = computed(() => {
  return patchPreview.value?.remove_keys?.length || 0
})
const patchCount = computed(() => patchKeyCount.value + patchRemoveCount.value)
const hasProposalPatch = computed(() => {
  if (!proposal.value) return false
  const patchN = proposal.value.profilePatch ? Object.keys(proposal.value.profilePatch).length : 0
  const removeN = proposal.value.remove_keys?.length || 0
  return (patchN + removeN) > 0
})
const confirmedSubtitle = computed(() => {
  const taskN = confirmedResult.value?.tasks?.length || proposalTaskCount.value
  const profileN = confirmedResult.value?.profileUpdates?.length
    || ((proposal.value?.profilePatch ? Object.keys(proposal.value.profilePatch).length : 0)
      + (proposal.value?.remove_keys?.length || 0))
  const parts = [`已生成 ${taskN} 条待办任务`]
  if (profileN > 0) parts.push(`档案更新 ${profileN} 项`)
  return parts.join('；') + '～'
})

// ====== 监听 visible：打开时 init；关闭时不强制 reset（允许用户再次打开继续）======
function deepCopy(o) { return o ? JSON.parse(JSON.stringify(o)) : null }

// ====== 各入口动作（统一调 store actions；二次确认在 store 内做，组件不重复弹）======
async function startChattingFromForm() {
  if (idleLoading.value) return
  idleLoading.value = true
  try {
    // 如果 initForm 是空的（用户没填创建表单就点 AI 拆解），给一个默认的引导消息
    if (!props.initForm || !String(props.initForm.title || '').trim()) {
      await store.init({ sessionType: 'TASK_CREATE', initForm: null })
      await store.sendUserMessage('请帮我设计一个家庭任务，先告诉我任务的背景和目标')
      return
    }
    await store.init({ sessionType: 'TASK_CREATE', initForm: deepCopy(props.initForm) })
    const title = props.initForm.title
    const desc  = props.initForm.description ? `，描述：${props.initForm.description}` : ''
    await store.sendUserMessage(`我想创建一个任务：${title}${desc}。请帮我分析并拆解成多个小步骤，同时建议更新哪些家庭成员档案？`)
  } finally {
    idleLoading.value = false
  }
}

// idle 态直接输入任务描述开始拆解
async function startFromIdleInput() {
  const text = idleInputText.value.trim()
  if (!text) return
  if (idleLoading.value) return
  idleLoading.value = true
  try {
    // 优先用 initForm（如果有），否则用用户输入的文本作为 title
    const initForm = (props.initForm && props.initForm.title)
      ? deepCopy(props.initForm)
      : { title: text }
    await store.init({ sessionType: 'TASK_CREATE', initForm })
    await store.sendUserMessage(text)
    idleInputText.value = ''
  } finally {
    idleLoading.value = false
  }
}

// ====== 访谈模式：创建 PROFILE_INTERVIEW 会话并让 AI 主动提第一个问题 ======
// D-1 会带上 initForm.assigneeProfileId → 后端 session.profileId → B-2 context 的
// assignee_profile_id，agent-service 依 context.session_type 走访谈分支。
async function startInterview() {
  if (idleLoading.value) return
  idleLoading.value = true
  try {
    const ok = await store.init({ sessionType: 'PROFILE_INTERVIEW', initForm: deepCopy(props.initForm) })
    if (!ok) return // 会话创建失败留在 idle，用户可点「开始 AI 访谈」重试
    await store.sendUserMessage('请开始访谈，逐个问题帮我完善这位家庭成员的档案。')
  } finally {
    idleLoading.value = false
  }
}

// 访谈模式进入页面即自动开始首轮（deep-link 场景老人不该再找按钮）
onMounted(() => {
  if (isInterview.value && store.isIdle && !store.sessionId) startInterview()
})

function runDemo()       { store.runOneClickDemo() }

function handleSend()    {
  const t = inputText.value
  inputText.value = ''
  store.sendUserMessage(t)
}

function generateDecompose() { store.generateDecompose() }

function backToChatting() {
  // 从 proposing 回退到 chatting，保留 messages/sessionId/initForm，
  // 清 proposal，用户可继续发消息让 AI 重新生成草稿。
  store.backToChatting()
}

function confirmProposal()   { store.confirmProposal().then(() => {
  if (store.isConfirmed) emit('confirmed', store.confirmedResult || {})
}) }

function saveOnlyProfile()   { store.saveOnlyProfile().then(() => {
  if (store.isArchived) emit('archived', store.archivedResult || {})
}) }

function cancelSession()     { store.cancelSession().then(() => {
  if (store.isCancelled) emit('cancelled')
}) }

function resetAllClean(silent) { store.resetAllClean(silent) }

// ====== 顶部关闭按钮（进行中调 Con7 confirmLeave；非进行态直接关）======
async function handleCloseBtn() {
  const ok = await store.confirmLeave()
  if (ok) emit('update:visible', false)
}

// ====== 终态后关闭面板（emit 对应事件给父 TaskView 刷新 list）======
function handleCloseAfterFinal(kind) {
  if (kind === 'confirmed') emit('confirmed', store.confirmedResult || {})
  else if (kind === 'archived') emit('archived', store.archivedResult || {})
  else if (kind === 'cancelled') emit('cancelled')
  emit('update:visible', false)
}

// ====== onBeforeUnmount：Con7 第 4/5 离开页面二次确认 ======
onBeforeUnmount(() => store.pauseStream())
</script>

<style scoped>
/* ========== 老人友好：大字号 + 高对比度 + 间距宽松 ========== */
.decompose-chat-panel {
  width: 100%;
  min-height: 600px;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 12px;
  font-size: 16px;
}
.dcp-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 24px;
  border-bottom: 1px solid #ebeef5;
  background: linear-gradient(135deg, #f5f7ff, #eef2ff);
}
.dcp-title { display: flex; align-items: center; flex-wrap: nowrap; gap: 8px; min-width: 0; }
.dcp-title-emoji { font-size: 36px; margin-right: 8px; flex-shrink: 0; }
.dcp-title-text  { margin: 0; font-size: 22px; font-weight: 700; color: #1f2937; white-space: nowrap; }
.dcp-status-tag  { font-size: 14px; flex-shrink: 0; }
.dcp-header-actions { display: flex; gap: 8px; flex-shrink: 0; }

.dcp-body {
  flex: 1 1 auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.dcp-btn-big { font-size: 16px !important; height: 44px !important; padding: 0 18px !important; }

/* ================== idle 空态 ================== */
.dcp-idle { text-align: center; padding: 20px 0; }
.dcp-idle-emoji { font-size: 96px; line-height: 1; margin: 20px 0; }
.dcp-idle-hint { font-size: 18px; line-height: 1.8; color: #374151; margin-bottom: 30px; }
.dcp-idle-actions { display: flex; gap: 20px; justify-content: center; flex-wrap: wrap; margin-bottom: 20px; }
.dcp-idle-initform { max-width: 720px; margin: 0 auto; }
/* idle 态直接输入框 */
.dcp-idle-input-wrap { max-width: 720px; margin: 0 auto 24px; display: flex; flex-direction: column; gap: 12px; }
.dcp-idle-input :deep(.el-textarea__inner) { font-size: 18px; line-height: 1.6; padding: 16px; }
.dcp-idle-input-actions { display: flex; justify-content: center; }

/* ================== chatting 消息区 ================== */
.dcp-chatting { display: flex; flex-direction: column; gap: 16px; }
.dcp-messages {
  display: flex; flex-direction: column; gap: 18px;
  max-height: 420px; overflow-y: auto; padding: 16px;
  border: 1px solid #eef0f4; border-radius: 12px; background: #fafbff;
}
.dcp-msg-row { display: flex; align-items: flex-start; gap: 12px; }
.dcp-msg-user { flex-direction: row-reverse; }
.dcp-msg-avatar { flex-shrink: 0; width: 44px; height: 44px; border-radius: 50%; background: #fff; display: flex; align-items: center; justify-content: center; box-shadow: 0 1px 4px rgba(0,0,0,0.06); }
.dcp-msg-bubble {
  max-width: calc(100% - 72px);
  padding: 12px 16px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(0,0,0,0.06);
}
.dcp-msg-user .dcp-msg-bubble { background: #e8f0ff; }
.dcp-msg-role { font-size: 13px; color: #9ca3af; margin-bottom: 6px; }
.dcp-msg-content { font-size: 17px; line-height: 1.7; color: #1f2937; white-space: pre-wrap; word-break: break-word; }
.dcp-typing-row { opacity: 0.9; }
.dcp-typing-bubble { min-width: 160px; }
.dcp-typing-dots { margin-top: 6px; color: #4f46e5; font-size: 16px; letter-spacing: 4px; }
.dcp-typing-dots .dot { animation: dcp-blink 1.2s infinite; display: inline-block; }
.dcp-typing-dots .d2 { animation-delay: 0.2s; }
.dcp-typing-dots .d3 { animation-delay: 0.4s; }
@keyframes dcp-blink {
  0%, 80%, 100% { opacity: 0.2; transform: scale(0.8); }
  40% { opacity: 1; transform: scale(1.1); }
}

.dcp-patch-preview { margin-top: 4px; }
.dcp-patch-detail { margin-top: 8px; }
.dcp-patch-item { font-size: 16px; line-height: 2; }
.dcp-patch-remove { color: #ef4444; }

/* 输入行 */
.dcp-input-row {
  display: flex; gap: 10px; align-items: center; flex-wrap: wrap;
  padding: 14px; border: 1px solid #eef0f4; border-radius: 12px; background: #fff;
}
.dcp-input { min-width: 260px; flex: 1 1 320px; }
.dcp-input :deep(.el-input__wrapper) { height: 48px; font-size: 16px; }

/* ================== proposing 态 ================== */
.dcp-proposing { display: flex; flex-direction: column; gap: 20px; }
.dcp-reasoning {
  padding: 16px 20px; border-radius: 12px; background: #fff7ed; border-left: 4px solid #f59e0b;
}
.dcp-reasoning-title { font-size: 17px; font-weight: 600; color: #92400e; margin-bottom: 10px; }
.dcp-reasoning-text {
  white-space: pre-wrap; word-break: break-word;
  font-size: 16px; line-height: 1.8; color: #451a03; margin: 0;
  background: #fff; padding: 12px 16px; border-radius: 8px; max-height: 240px; overflow-y: auto;
  font-family: inherit;
}

.dcp-proposal-card {
  border: 1px solid #e5e7eb; border-radius: 14px; padding: 20px; background: #fff;
  box-shadow: 0 2px 10px rgba(79,70,229,0.05);
}
.dcp-proposal-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 20px; flex-wrap: wrap; margin-bottom: 16px; }
.dcp-proposal-title { margin: 0; font-size: 20px; color: #1f2937; }
.dcp-proposal-title span { color: #4f46e5; }
.dcp-proposal-desc  { font-size: 16px; color: #4b5563; margin-top: 6px; }
.dcp-proposal-table { margin-top: 8px; }
.dcp-proposal-patch { margin-top: 20px; padding: 14px 18px; background: #f0fdf4; border-radius: 10px; border-left: 4px solid #10b981; }
.dcp-proposal-patch h5 { margin: 0 0 8px; font-size: 17px; color: #065f46; }

.dcp-proposal-empty { padding: 20px 0; }

.dcp-final-actions {
  display: flex; gap: 12px; flex-wrap: wrap; justify-content: center;
  padding: 14px; background: #f9fafb; border-radius: 12px;
}

/* ================== el-result 终态 ================== */
.dcp-result { padding: 40px 20px; }
.dcp-result :deep(.el-result__title) { font-size: 22px !important; }
.dcp-result :deep(.el-result__subtitle) { font-size: 17px !important; }
.dcp-result :deep(.el-result__extra) { display: flex; gap: 16px; justify-content: center; flex-wrap: wrap; padding-top: 20px; }

/* ================== 移动端适配（≤768px） ================== */
@media (max-width: 768px) {
  .decompose-chat-panel { min-height: 480px; }
  .dcp-header {
    padding: 12px 14px;
    flex-wrap: wrap;
    gap: 10px;
  }
  /* 窄屏下标题区和操作区分两行，避免标题被挤折行 */
  .dcp-title { flex: 1 1 100%; }
  .dcp-title-emoji { font-size: 28px; }
  .dcp-title-text { font-size: 19px; }
  .dcp-header-actions { width: 100%; justify-content: flex-end; }
  .dcp-body { padding: 14px; gap: 14px; }
  /* 消息区高度放大，聊天是核心区域 */
  .dcp-messages { max-height: 52vh; padding: 12px; }
  .dcp-msg-avatar { width: 36px; height: 36px; }
  .dcp-msg-bubble { max-width: calc(100% - 56px); padding: 10px 12px; }
  .dcp-msg-content { font-size: 16px; }
  /* idle 空态 emoji 缩小，避免占满首屏 */
  .dcp-idle-emoji { font-size: 64px; margin: 10px 0; }
  .dcp-idle-hint { font-size: 16px; margin-bottom: 20px; }
  .dcp-idle-actions { gap: 12px; }
  .dcp-idle-actions :deep(.el-button) { flex: 1 1 100%; }
  /* 推理区紧凑化 */
  .dcp-reasoning { padding: 12px 14px; }
  .dcp-reasoning-text { font-size: 15px; line-height: 1.7; max-height: 30vh; }
  /* 输入行：输入框占满一行，发送按钮独占一行，避免 260px min-width 换行生硬 */
  .dcp-input-row { padding: 10px; }
  .dcp-input { min-width: 0; flex: 1 1 100%; }
  .dcp-input-row :deep(.el-button) { flex: 1 1 auto; }
  .dcp-proposal-card { padding: 14px; }
  .dcp-final-actions { gap: 8px; }
  .dcp-final-actions :deep(.el-button) { flex: 1 1 40%; }
}
</style>
