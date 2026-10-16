<template>
  <div class="templates-view">
    <h1 class="page-title">📋 规则模板管理</h1>
    <el-tabs v-model="activeTab" class="tabs">
      <!-- Tab1: 模板列表 -->
      <el-tab-pane label="模板列表" name="list">
        <el-table :data="tplStore.list" size="large" empty-text="暂无模板" v-loading="tplStore.loading">
          <el-table-column prop="name" label="名称" min-width="150" />
          <el-table-column prop="type" label="类型" width="100" />
          <el-table-column prop="version" label="版本" width="80" />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button size="small" @click="loadVersions(row.id)">查看版本</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Tab2: 版本历史 -->
      <el-tab-pane label="版本历史" name="versions">
        <el-table :data="tplStore.versions" size="large" empty-text="暂无版本" v-loading="tplStore.loading">
          <el-table-column prop="version" label="版本" width="80" />
          <el-table-column prop="change_summary" label="变更说明" min-width="200" />
          <el-table-column prop="is_important" label="重要" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.is_important" type="danger">★</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button size="small" @click="tplStore.star(row.version)">★</el-button>
              <el-button size="small" type="danger" @click="tplStore.del(row.version)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Tab3: 选模板给任务 -->
      <el-tab-pane label="选模板给任务" name="pick">
        <el-form label-width="120px" class="pick-form">
          <el-form-item label="模板 ID">
            <el-input v-model="pickForm.templateId" placeholder="输入模板 ID" size="large" />
          </el-form-item>
          <el-form-item label="版本 ID">
            <el-input v-model="pickForm.versionId" placeholder="输入版本 ID" size="large" />
          </el-form-item>
          <el-form-item label="任务 ID">
            <el-input v-model="pickForm.taskId" placeholder="输入任务 ID" size="large" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" size="large" class="big-btn" @click="doPick">
              ✅ 选定模板
            </el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useTemplatesStore } from '@/stores/templates'
import { ElMessage } from 'element-plus'

const tplStore = useTemplatesStore()
const activeTab = ref('list')
const pickForm = ref({ templateId: '', versionId: '', taskId: '' })

onMounted(() => { tplStore.load() })

async function loadVersions(id) {
  activeTab.value = 'versions'
  await tplStore.loadVersions(id)
}

async function doPick() {
  try {
    await tplStore.pick(pickForm.value.templateId, pickForm.value.versionId, pickForm.value.taskId)
    ElMessage.success('模板选定成功')
  } catch (e) {
    ElMessage.error(e.message || '选定失败')
  }
}
</script>

<style scoped>
.templates-view { padding: 24px 32px; font-family: system-ui, "PingFang SC", "Microsoft YaHei", sans-serif; }
.page-title { font-size: 36px; font-weight: 700; margin: 0 0 24px 0; }
.tabs { font-size: 18px; }
.pick-form { max-width: 500px; margin: 24px auto; }
.big-btn { min-height: 56px; font-size: 20px; }
/* 移动端：外层 AppLayout 已给 12px 内边距，这里归零避免双重挤压 */
@media (max-width: 768px) {
  .templates-view { padding: 0; }
  .page-title { font-size: 26px; margin-bottom: 16px; }
}
</style>
