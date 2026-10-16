<template>
  <div class="task-form-page">
    <!-- 顶部导航栏：返回任务列表 + 页面标题 -->
    <div class="form-nav">
      <el-button size="large" @click="goBack">🔙 返回任务列表</el-button>
      <h2 class="form-nav-title">➕ 创建新任务</h2>
      <div style="min-width: 130px;"></div>
    </div>

    <!-- 创建任务表单（从原 TaskView 抽出，逻辑保持一致） -->
    <el-card class="create-card">
      <el-form :model="form" label-position="top" :disabled="saving" :aria-busy="saving">
        <el-form-item label="标题" required>
          <el-input
            v-model="form.title"
            size="large"
            maxlength="200"
            show-word-limit
            placeholder="任务标题，必填（最多 200 字）"
          />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            placeholder="详细描述（可选）"
          />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.categoryId" size="large" style="width: 100%" placeholder="请选择分类（选填）">
            <el-option label="（不分类）" :value="null" />
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="12">
            <el-form-item label="截止日期">
              <el-date-picker
                v-model="form.dueDate"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="选择日期（可选）"
                style="width: 100%"
                size="large"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="优先级">
              <el-select v-model="form.priority" size="large" style="width: 100%">
                <el-option label="低" value="LOW" />
                <el-option label="中" value="MEDIUM" />
                <el-option label="高" value="HIGH" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="指派给家庭成员">
          <el-select v-model="form.assigneeProfileId" size="large" style="width: 100%" placeholder="请选择家庭成员（选填）" clearable>
            <el-option
              v-for="p in profileOptions"
              :key="p.id"
              :label="p.name"
              :value="p.id"
            >
              <div style="display: flex; align-items: center; gap: 10px; font-size: 16px;">
                <EmojiAvatar :emoji="p.avatarEmoji" :profile-type="p.profileType" size="sm" />
                <span>{{ p.name }}</span>
                <TypeBadge :profile-type="p.profileType" size="sm" />
                <span v-if="p.relation" style="color: #909399; font-size: 14px;">（{{ p.relation }}）</span>
              </div>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            size="large"
            :disabled="saving || !form.title.trim()"
            :loading="saving"
            @click="submitCreate"
          >
            {{ saving ? '正在保存…' : '➕ 创建任务' }}
          </el-button>
          <el-button
            type="warning"
            size="large"
            data-ai="true"
            class="ai-entry-btn"
            :disabled="!form.title.trim()"
            @click="startAiDecompose"
          >
            🤖 AI 拆解新增
          </el-button>
          <el-button size="large" @click="resetForm">重置</el-button>
        </el-form-item>
      </el-form>
      <p v-if="saveError" role="alert">{{ saveError }} <RouterLink to="/tasks">查看任务列表</RouterLink></p>
    </el-card>

    <!-- 任务推荐面板（当选择了家庭成员或分类时出现） -->
    <SuggestionsPanel
      v-if="form.assigneeProfileId || form.categoryId"
      :similar-completed-tasks="suggestions.similarCompletedTasks"
      :similar-templates="suggestions.similarTemplates"
      :pending-tasks="suggestions.pendingTasks"
      :loading="loadingSuggestions"
      @apply-task="applySuggestedTask"
    />

    <!-- AI 拆解 6 态对话面板（v-if 控制 DOM 卸载 = 组件 onBeforeUnmount 自动 resetAllClean 防串台 Con9） -->
    <DecomposeChatPanel
      v-if="decomposeVisible"
      v-model:visible="decomposeVisible"
      :init-form="decomposeInitForm"
      @confirmed="handleDecomposeConfirmed"
      @archived="handleDecomposeFinalized"
      @cancelled="handleDecomposeFinalized"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { tasksApi } from '../api/tasks'
import { categoryApi } from '../api/categories'
import { listProfiles } from '../api/profiles'
import { getProfileSuggestions } from '../api/suggestions'
import SuggestionsPanel from '../components/SuggestionsPanel.vue'
import EmojiAvatar from '../components/EmojiAvatar.vue'
import TypeBadge from '../components/TypeBadge.vue'
import DecomposeChatPanel from '../components/DecomposeChatPanel.vue'

const router = useRouter()
const saving = ref(false)
const saveError = ref('')
let active = true
onBeforeUnmount(() => { active = false })

// ===== 表单定义（与原 TaskView 一致：双字段兼容，只写新 assigneeProfileId） =====
const form = reactive({
  title: '',
  description: '',
  dueDate: '',
  priority: 'MEDIUM',
  categoryId: null,
  assigneeProfileId: null
})

// ===== 下拉 & 推荐面板状态 =====
const categories = ref([])
const profileOptions = ref([])
const loadingSuggestions = ref(false)
const suggestions = reactive({
  similarCompletedTasks: [],
  similarTemplates: [],
  pendingTasks: []
})
const decomposeVisible = ref(false)
// ===== AI 拆解入口快照：创建表单的 6 字段深拷贝 =====
const decomposeInitForm = computed(() => ({
  title: String(form.title || ''),
  description: String(form.description || ''),
  dueDate: String(form.dueDate || ''),
  priority: String(form.priority || 'MEDIUM'),
  categoryId: form.categoryId,
  assigneeProfileId: form.assigneeProfileId
}))

// ===== 双兼容工具：API 返回可能是 Array 或 {items:Array} =====
function normalizeArray(data) {
  if (Array.isArray(data)) return data
  if (data && Array.isArray(data.items)) return data.items
  return []
}

async function loadCategories() {
  try {
    const data = await categoryApi.list()
    categories.value = normalizeArray(data)
  } catch (e) { /* 拦截器已提示，降级为不分类 */ }
}

async function loadProfileOptions() {
  try {
    const data = await listProfiles()
    profileOptions.value = normalizeArray(data)
  } catch (e) { /* 拦截器已提示，降级为不下拉 */ }
}

async function fetchSuggestions() {
  if (!form.assigneeProfileId && !form.categoryId) {
    suggestions.similarCompletedTasks = []
    suggestions.similarTemplates = []
    suggestions.pendingTasks = []
    return
  }
  loadingSuggestions.value = true
  try {
    if (form.assigneeProfileId) {
      const data = await getProfileSuggestions(form.assigneeProfileId, form.categoryId)
      suggestions.similarCompletedTasks = Array.isArray(data?.similarCompletedTasks) ? data.similarCompletedTasks : []
      suggestions.similarTemplates = Array.isArray(data?.similarTemplates) ? data.similarTemplates : []
      suggestions.pendingTasks = Array.isArray(data?.pendingTasks) ? data.pendingTasks : []
    }
  } catch (e) {
    suggestions.similarCompletedTasks = []
    suggestions.similarTemplates = []
    suggestions.pendingTasks = []
  } finally {
    loadingSuggestions.value = false
  }
}

// ===== 提交创建（payload 绝对不包含旧 assigneeId） =====
async function submitCreate() {
  if (saving.value || !form.title.trim()) return
  saving.value = true
  saveError.value = ''
  const payload = {
    title: form.title.trim(),
    priority: form.priority || 'MEDIUM'
  }
  if (form.description) payload.description = form.description
  if (form.dueDate) payload.dueDate = form.dueDate
  if (form.categoryId != null) payload.categoryId = form.categoryId
  if (form.assigneeProfileId != null) payload.assigneeProfileId = form.assigneeProfileId
  // ⚠️ 双字段兼容约束：绝对不写旧 assigneeId，防 40010
  try {
    await tasksApi.create(payload)
    if (!active) return
    ElMessage.success('任务创建成功，已返回任务列表')
    // 创建成功后返回任务列表（老人闭环体验）
    await router.push('/tasks')
  } catch (e) {
    if (active && e?.code !== 'ERR_CANCELED') {
      saveError.value = '保存结果未确认，内容已保留。请先查看列表，确认未添加后再试。'
    }
  } finally {
    saving.value = false
  }
}

function resetForm() {
  form.title = ''
  form.description = ''
  form.dueDate = ''
  form.priority = 'MEDIUM'
  form.categoryId = null
  form.assigneeProfileId = null
  decomposeVisible.value = false
  suggestions.similarCompletedTasks = []
  suggestions.similarTemplates = []
  suggestions.pendingTasks = []
}

// ===== AI 拆解入口 =====
function startAiDecompose() {
  if (!form.title.trim()) {
    ElMessage.warning('请先填写任务标题再使用 AI 拆解')
    return
  }
  decomposeVisible.value = true
}

// ===== DecomposeChatPanel 终态事件回调 =====
async function handleDecomposeConfirmed(/* result */) {
  ElMessage.success('AI 拆解已完成，已返回任务列表查看新任务')
  router.push('/tasks')
}
function handleDecomposeFinalized() {
  // archived/cancelled：静默返回任务列表
  router.push('/tasks')
}

// ===== 应用推荐任务到表单 =====
function applySuggestedTask(taskLike) {
  if (taskLike?.title) form.title = String(taskLike.title)
  if (taskLike?.description) form.description = String(taskLike.description)
  if (taskLike?.priority) form.priority = String(taskLike.priority)
  if (taskLike?.dueDate) form.dueDate = String(taskLike.dueDate)
  if (taskLike?.categoryId != null) form.categoryId = taskLike.categoryId
  ElMessage.success('已填入推荐内容，请检查后创建或交给 AI 拆解')
}

function goBack() {
  router.push('/tasks')
}

// ===== 监听 assigneeProfileId / categoryId 变化，自动拉取推荐 =====
watch(
  () => [form.assigneeProfileId, form.categoryId],
  () => { fetchSuggestions() },
  { deep: false }
)

onMounted(async () => {
  await Promise.all([loadCategories(), loadProfileOptions()])
})
</script>

<style scoped>
.task-form-page {
  max-width: 900px;
  margin: 0 auto;
}
.form-nav {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 0;
  margin-bottom: 16px;
  background: linear-gradient(135deg, #eef2ff, #e0e7ff);
  border-radius: 12px;
  padding: 16px 20px;
}
.form-nav-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: #1e1b4b;
}
.create-card {
  padding: 12px 16px 16px;
}
.create-card :deep(.el-form-item:last-child .el-form-item__content) {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
/* 移动端：导航栏改为垂直 */
@media (max-width: 768px) {
  .form-nav {
    flex-direction: column;
    gap: 12px;
    text-align: center;
  }
  .form-nav-title { font-size: 20px; }
  .form-nav > div[style] { display: none; }
}
</style>
