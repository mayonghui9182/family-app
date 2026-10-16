<template>
  <section class="draft-page" aria-live="polite">
    <h1>确认待办</h1>
    <p v-if="loading" role="status">正在读取你的安排…</p>
    <div v-else-if="error" role="alert">
      <p>{{ error }}</p><el-button size="large" @click="load">重试一下</el-button>
      <p>如果选错了家庭，请在顶部切换后再试。</p>
    </div>
    <template v-else-if="draft">
      <p v-if="draft.init_task_title">你说：{{ draft.init_task_title }}</p>
      <p v-if="draft.status === 'CONFIRMED'">这些待办已经创建，请勿重复添加。</p>
      <p v-else-if="draft.status === 'CANCELLED'">这份安排已取消。</p>
      <p v-else-if="!rows.length">暂时还没有拆解出待办，请稍后重试。</p>
      <ol>
        <li v-for="(task, index) in rows" :key="index">
          <template v-if="draft.status === 'ACTIVE'">
            <label :for="`draft-title-${index}`">第 {{ index + 1 }} 件事</label>
            <el-input :id="`draft-title-${index}`" v-model="task.title" :disabled="busy" maxlength="200" />
            <el-input v-model="task.description" type="textarea" :disabled="busy" aria-label="补充说明" />
            <el-button :disabled="busy" @click="rows.splice(index, 1)">移除这条</el-button>
          </template>
          <template v-else>
            <strong>{{ task.title }}</strong>
            <p v-if="task.description">{{ task.description }}</p>
            <RouterLink v-if="task.created_task_id" :to="`/tasks/${task.created_task_id}/edit`">查看这件事</RouterLink>
          </template>
          <p v-if="task.due_date">截止：{{ task.due_date }}</p>
        </li>
      </ol>
      <div v-if="draft.status === 'ACTIVE'" class="draft-actions">
        <el-button type="primary" size="large" :loading="busy" :disabled="!rows.length || busy" @click="accept">
          确认添加 {{ rows.length }} 件事
        </el-button>
        <el-button size="large" :disabled="busy" @click="discard">不要了</el-button>
      </div>
      <p v-if="actionError" role="alert">{{ actionError }}</p>
      <RouterLink to="/tasks">查看任务列表</RouterLink>
    </template>
  </section>
</template>
<script setup>
import { ref, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { getSession, updateProposal, confirm, cancel } from '../api/decomposeSession'
const route = useRoute()
const draft = ref(null)
const rows = ref([])
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const actionError = ref('')
let generation = 0
onBeforeUnmount(() => { generation += 1 })
async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = await getSession(route.params.id)
    if (current !== generation) return
    draft.value = result
    rows.value = (result?.proposed_tasks || []).map(task => ({ ...task }))
  } catch (e) {
    if (current === generation && e?.code !== 'ERR_CANCELED') error.value = '这份安排暂时无法读取，请确认家庭后重试。'
  } finally {
    if (current === generation) loading.value = false
  }
}
async function accept() {
  if (busy.value || draft.value?.status !== 'ACTIVE') return
  if (rows.value.some(task => !task.title?.trim())) {
    actionError.value = '请为每件事填写名称。'
    return
  }
  const current = generation
  const sessionId = route.params.id
  busy.value = true
  actionError.value = ''
  try {
    await updateProposal(sessionId, rows.value)
    if (current !== generation) return
    await confirm(sessionId)
    if (current !== generation) return
    await load()
  } catch (e) {
    if (current !== generation) return
    actionError.value = '尚未确认添加结果，请重新读取这份安排后查看。'
    await load()
  } finally { busy.value = false }
}
async function discard() {
  if (busy.value) return
  const current = generation
  const sessionId = route.params.id
  try {
    await ElMessageBox.confirm('确定不要这份安排吗？', '取消安排', {
      confirmButtonText: '不要了', cancelButtonText: '保留安排'
    })
  } catch { return }
  if (current !== generation) return
  busy.value = true
  try {
    await cancel(sessionId)
    if (current === generation) await load()
  }
  catch { if (current === generation) actionError.value = '取消未成功，请重试。' }
  finally { busy.value = false }
}
watch(() => route.params.id, load, { immediate: true })
</script>
<style scoped>
.draft-page { max-width: 680px; margin: auto; font-size: 18px; color: #1f2937; line-height: 1.7; }
.draft-page li { margin-bottom: 20px; padding: 16px; background: #fff; border-radius: 12px; }
.draft-page :deep(.el-button) { min-height: 48px; font-size: 18px; }
.draft-page :deep(.el-input), .draft-page :deep(.el-textarea) { margin: 8px 0; }
.draft-page :deep(.el-input__inner) { min-height: 48px; font-size: 18px; }
.draft-page :deep(.el-textarea__inner) { min-height: 72px; font-size: 18px; }
.draft-page a { display: inline-block; padding: 12px 0; color: #1d4ed8; }
.draft-page li a { display: block; }
.draft-actions { display: flex; flex-wrap: wrap; gap: 12px; }
</style>
