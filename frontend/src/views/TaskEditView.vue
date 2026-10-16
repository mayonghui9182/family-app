<template>
  <div class="task-form-page">
    <!-- 顶部导航栏：返回任务列表 + 页面标题 -->
    <div class="form-nav">
      <el-button size="large" @click="goBack">🔙 返回任务列表</el-button>
      <h2 class="form-nav-title">✏️ 编辑任务</h2>
      <div style="min-width: 130px;"></div>
    </div>

    <el-card v-loading="loading" class="edit-card">
      <el-form :model="form" label-position="top">
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
            :disabled="!form.title.trim() || loading"
            @click="submitUpdate"
          >
            💾 保存修改
          </el-button>
          <el-button size="large" @click="goBack">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { tasksApi } from '../api/tasks'
import { categoryApi } from '../api/categories'
import { listProfiles } from '../api/profiles'
import EmojiAvatar from '../components/EmojiAvatar.vue'
import TypeBadge from '../components/TypeBadge.vue'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const form = reactive({
  title: '',
  description: '',
  dueDate: '',
  priority: 'MEDIUM',
  categoryId: null,
  assigneeProfileId: null
})
const categories = ref([])
const profileOptions = ref([])

function normalizeArray(data) {
  if (Array.isArray(data)) return data
  if (data && Array.isArray(data.items)) return data.items
  return []
}

async function loadTask() {
  loading.value = true
  try {
    const id = route.params.id
    const t = await tasksApi.get(id)
    form.title = t.title || ''
    form.description = t.description || ''
    form.dueDate = t.dueDate || ''
    form.priority = t.priority || 'MEDIUM'
    form.categoryId = t.categoryId ?? null
    form.assigneeProfileId = t.assigneeProfileId ?? null
  } catch (e) {
    // 拦截器已提示，返回任务列表
    router.push('/tasks')
  } finally {
    loading.value = false
  }
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

async function submitUpdate() {
  if (!form.title.trim()) return
  const payload = {
    title: form.title.trim(),
    priority: form.priority || 'MEDIUM'
  }
  if (form.description) payload.description = form.description
  if (form.dueDate) payload.dueDate = form.dueDate
  if (form.categoryId != null) payload.categoryId = form.categoryId
  payload.assigneeProfileId = form.assigneeProfileId ?? null
  try {
    await tasksApi.update(route.params.id, payload)
    ElMessage.success('任务已保存')
    router.push('/tasks')
  } catch (e) {
    // 拦截器已提示，停留让用户重试
  }
}

function goBack() {
  router.push('/tasks')
}

onMounted(async () => {
  await Promise.all([loadTask(), loadCategories(), loadProfileOptions()])
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
  padding: 16px 20px;
  margin-bottom: 16px;
  background: linear-gradient(135deg, #eef2ff, #e0e7ff);
  border-radius: 12px;
}
.form-nav-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: #1e1b4b;
}
.edit-card {
  padding: 12px 16px 16px;
}
.edit-card :deep(.el-form-item:last-child .el-form-item__content) {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
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
