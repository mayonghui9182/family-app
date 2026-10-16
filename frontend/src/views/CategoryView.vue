<template>
  <el-card class="category-card" role="region" aria-label="分类管理：添加分类/合并分类/分类列表与操作">
    <template #header>
      <div class="card-header">
        <span class="title">🗂️ 分类管理</span>
        <el-tag v-if="!isMobile && currentFamily" type="info" size="large">当前家庭：{{ currentFamily.name }}</el-tag>
      </div>
    </template>

    <el-alert
      v-if="!currentFamilyId"
      title="请先在「家庭管理」中选择一个家庭，才能管理分类"
      type="warning"
      :closable="false"
      show-icon
    />

    <template v-else>
      <el-form :inline="true" class="create-form" @submit.prevent>
        <el-form-item label="分类名称">
          <el-input
            v-model="createForm.name"
            placeholder="如：家务、学习"
            clearable
            size="large"
            style="width: 220px"
            @keyup.enter="handleCreate"
          />
        </el-form-item>
        <el-form-item label="颜色">
          <el-color-picker v-model="createForm.color" size="large" aria-label="选择这个分类的标识颜色" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="large" :loading="creating" @click="handleCreate">➕ 添加分类</el-button>
        </el-form-item>
      </el-form>

      <el-collapse class="merge-collapse" aria-label="合并分类工具：把一个分类下的所有任务移动到另一个分类">
        <el-collapse-item title="🔀 合并分类（把一个分类的任务合并到另一个）" name="merge">
          <el-form :inline="true">
            <el-form-item label="源分类（将被合并）">
              <el-select v-model="mergeForm.fromId" placeholder="选择源分类" size="large" style="width: 200px">
                <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="合并到">
              <el-select v-model="mergeForm.toId" placeholder="选择目标分类" size="large" style="width: 200px">
                <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="warning" size="large" :loading="merging" :disabled="!canMerge" aria-label="确认从分类合并到目标分类" @click="handleMerge">🔀 确认合并分类</el-button>
            </el-form-item>
          </el-form>
        </el-collapse-item>
      </el-collapse>

      <el-table :data="categories" v-loading="loading" stripe size="large" role="table" aria-label="分类列表：颜色/名称/关联任务数/编辑删除操作">
        <el-table-column label="颜色" width="80" align="center">
          <template #default="{ row }">
            <span class="color-block" aria-hidden="true" :style="{ background: row.color || '#909399' }"></span>
            <span class="sr-only">颜色{{ row.color }}</span>
          </template>
        </el-table-column>
        <el-table-column label="分类名称" prop="name" min-width="180" />
        <el-table-column v-if="hasTaskCount" label="关联任务数" prop="taskCount" width="120" align="center" />
        <el-table-column label="操作" width="320" align="center">
          <template #default="{ row }">
            <el-button size="large" :aria-label="`编辑分类${row.name}的名称`" @click="openEdit(row)">✏️ 编辑分类名</el-button>
            <el-button size="large" type="danger" :aria-label="`确认删除分类${row.name}`" @click="handleDelete(row)">🗑️ 删除该分类</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && categories.length === 0" description="还没有分类呢，点上方【➕ 添加分类】建立买菜/吃药/缴费 这些常用分类吧" aria-label="还没有分类，点上方添加分类按钮建立常用分类" />
    </template>

    <el-dialog v-model="editVisible" title="编辑分类" width="420px">
      <el-form label-width="80px">
        <el-form-item label="名称">
          <el-input v-model="editForm.name" size="large" />
        </el-form-item>
        <el-form-item label="颜色">
          <el-color-picker v-model="editForm.color" size="large" aria-label="选择这个分类的标识颜色" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="large" @click="editVisible = false">取消</el-button>
        <el-button type="primary" size="large" :loading="saving" @click="handleSaveEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="mergeOnDeleteVisible" title="该分类有关联任务" width="420px">
      <p style="font-size:15px;line-height:1.8">
        删除「<b>{{ pendingDelete?.name }}</b>」失败，可能因为它还有关联任务。<br />
        请选择一个目标分类，将该分类下的任务合并过去后再删除：
      </p>
      <el-select v-model="mergeOnDeleteTo" placeholder="选择合并目标" size="large" style="width:100%;margin-top:12px">
        <el-option v-for="c in deleteMergeTargets" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <template #footer>
        <el-button size="large" @click="mergeOnDeleteVisible = false">取消</el-button>
        <el-button type="danger" size="large" :loading="deleting" @click="confirmDeleteWithMerge">合并并删除</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { categoryApi } from '../api/categories'
import { useFamilyStore } from '../stores/family'
import { useIsMobile } from '../composables/useIsMobile'

const familyStore = useFamilyStore()
const isMobile = useIsMobile()
const currentFamily = computed(() => familyStore.currentFamily)
const currentFamilyId = computed(() => familyStore.currentFamilyId)

const categories = ref([])
const loading = ref(false)

const createForm = reactive({ name: '', color: '#409EFF' })
const creating = ref(false)

const editVisible = ref(false)
const editForm = reactive({ id: null, name: '', color: '#409EFF' })
const saving = ref(false)

const mergeForm = reactive({ fromId: null, toId: null })
const merging = ref(false)
const canMerge = computed(() => mergeForm.fromId && mergeForm.toId && mergeForm.fromId !== mergeForm.toId)

const mergeOnDeleteVisible = ref(false)
const pendingDelete = ref(null)
const mergeOnDeleteTo = ref(null)
const deleting = ref(false)
const deleteMergeTargets = computed(() => {
  if (!pendingDelete.value) return []
  return categories.value.filter((c) => c.id !== pendingDelete.value.id)
})

const hasTaskCount = computed(() => categories.value.some((c) => 'taskCount' in c))

async function load() {
  if (!currentFamilyId.value) return
  loading.value = true
  try {
    categories.value = (await categoryApi.list()) || []
  } finally {
    loading.value = false
  }
}

async function handleCreate() {
  if (!createForm.name.trim()) {
    ElMessage.warning('请输入分类名称')
    return
  }
  creating.value = true
  try {
    await categoryApi.create({ name: createForm.name.trim(), color: createForm.color })
    ElMessage.success('已添加分类')
    createForm.name = ''
    await load()
  } finally {
    creating.value = false
  }
}

function openEdit(row) {
  editForm.id = row.id
  editForm.name = row.name
  editForm.color = row.color || '#409EFF'
  editVisible.value = true
}

async function handleSaveEdit() {
  if (!editForm.name.trim()) {
    ElMessage.warning('请输入分类名称')
    return
  }
  saving.value = true
  try {
    await categoryApi.update(editForm.id, { name: editForm.name.trim(), color: editForm.color })
    ElMessage.success('已保存')
    editVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function handleMerge() {
  if (!canMerge.value) return
  const from = categories.value.find(c => c.id === mergeForm.fromId)
  const to   = categories.value.find(c => c.id === mergeForm.toId)
  try {
    await ElMessageBox.confirm(
      '确认把分类「' + (from?.name || '') + '」下的所有任务合并到「' + (to?.name || '') + '」吗？\n合并后原分类不会被删除，任务只是换了归属。',
      '🔀 合并分类确认',
      { confirmButtonText: '确认合并', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (e) { return }
  merging.value = true
  try {
    await categoryApi.merge(mergeForm.fromId, mergeForm.toId)
    ElMessage.success('已完成合并')
    mergeForm.fromId = null; mergeForm.toId = null
    await load()
  } finally {
    merging.value = false
  }
}

async function handleDelete(row) {
  if (!row) return
  try {
    await ElMessageBox.confirm(
      '确认删除分类「' + (row.name || '') + '」吗？如果它下面还有任务，删除会失败后自动提示合并。',
      '🗑️ 删除分类确认',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (e) { return }
  pendingDelete.value = row
  try {
    await categoryApi.remove(row.id)
    ElMessage.success('分类已删除')
    pendingDelete.value = null
    await load()
  } catch (e) {
    pendingDelete.value = row
    mergeOnDeleteTo.value = null
    const targets = (categories.value || []).filter(c => c.id !== row.id)
    if (targets.length > 0) {
      deleteMergeTargets.value
      mergeOnDeleteVisible.value = true
    }
  }
}

async function confirmDeleteWithMerge() {
  if (!mergeOnDeleteTo.value || !pendingDelete.value) return
  const to = categories.value.find(c => c.id === mergeOnDeleteTo.value)
  try {
    await ElMessageBox.confirm(
      '确认把「' + (pendingDelete.value?.name || '') + '」的任务合并到「' + (to?.name || '') + '」后删除原分类吗？\n这一步不可恢复哦。',
      '⚠️ 合并后删除（不可恢复）',
      { confirmButtonText: '确认合并并删除', cancelButtonText: '取消', type: 'error' }
    )
  } catch (e) { return }
  deleting.value = true
  try {
    await categoryApi.merge(pendingDelete.value.id, mergeOnDeleteTo.value)
    await categoryApi.remove(pendingDelete.value.id)
    ElMessage.success('已合并并删除该分类')
    mergeOnDeleteVisible.value = false
    pendingDelete.value = null
    mergeOnDeleteTo.value = null
    await load()
  } finally {
    deleting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.category-card {
  max-width: 960px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.card-header .title {
  font-size: 20px;
  font-weight: 600;
}
.create-form {
  margin-bottom: 8px;
}
/* 移动端：创建表单改为垂直布局，输入框占满宽度 */
@media (max-width: 768px) {
  .create-form :deep(.el-form-item) {
    display: block;
    margin-right: 0;
    width: 100%;
  }
  .create-form :deep(.el-form-item__label) {
    display: block;
    text-align: left;
    margin-bottom: 6px;
  }
  .create-form :deep(.el-input) {
    width: 100% !important;
  }
  .create-form :deep(.el-button) {
    width: 100%;
  }
}
.merge-collapse {
  margin-bottom: 16px;
}
.color-block {
  display: inline-block;
  width: 26px;
  height: 26px;
  border-radius: 6px;
  border: 1px solid #dcdfe6;
  vertical-align: middle;
}
</style>