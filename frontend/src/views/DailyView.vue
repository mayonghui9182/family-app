<template>
  <div class="daily-view" aria-labelledby="daily-title">
    <h1 id="daily-title">我的一天</h1>
    <p>北京时间 08:30 安排重点，21:00 回顾进展。</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="settings && !settings.available">每日计划尚未启用，请联系管理员。</p>
    <template v-if="settings?.available">
      <label class="setting"><input type="checkbox" :checked="settings.enabled" :disabled="busy" @change="toggle($event.target.checked)" />每天自动生成计划与复盘</label>
      <p>记录会保存在这里；当前不发送主动提醒。</p>
      <label>查看日期 <input v-model="date" type="date" :disabled="busy" @change="load" /></label>
      <button :disabled="busy" @click="load">刷新</button>
      <section aria-labelledby="plan-title">
        <h2 id="plan-title">{{ date === today ? '今日计划' : `${date} 的计划` }}</h2>
        <template v-if="plan && !plan.missing">
          <p>{{ plan.confirmed ? '已确认' : '待确认，可调整重点' }} · {{ formatTime(plan.generatedAt) }}</p>
          <p v-if="plan.content.late">本次在 08:30 后生成，请按实际安排调整。</p>
          <p v-if="!plan.content.baseline">生成时已过 21:00，此计划不会作为早晨复盘基准。</p>
          <ol>
            <li v-for="(item, index) in items" :key="item.key || index">
              <template v-if="item.unavailable">原任务已不可访问。</template>
              <template v-else>
                <label><input v-model="item.focus" type="checkbox" :disabled="busy || date !== today" />重点：{{ item.title }}</label>
                <p>{{ sourceLabel(item.source) }} · {{ statusLabel(item.status) }} · {{ item.dueDate || '未设截止日期' }} {{ item.time || '' }}</p>
                <button :disabled="busy || index === 0 || date !== today" :aria-label="`将${item.title}上移`" @click="move(index)">上移</button>
                <button :disabled="busy || date !== today" :aria-label="`将${item.title}移出今日计划`" @click="items.splice(index, 1)">移出今日计划</button>
              </template>
            </li>
          </ol>
          <p v-if="!items.length">暂无计划项目。你可以先在任务页面添加待办。</p>
          <button :disabled="busy || date !== today" @click="save">保存调整</button>
          <button :disabled="busy || date !== today" @click="confirm">保存并确认计划</button>
        </template>
        <template v-else-if="plan?.missing"><p>这一天尚未生成计划。</p><button v-if="date === today" :disabled="busy" @click="generatePlan">现在生成计划</button></template>
        <p v-else>计划尚未加载，请刷新重试。</p>
      </section>
      <section aria-labelledby="review-title">
        <h2 id="review-title">晚间复盘</h2>
        <template v-if="review && !review.missing">
          <p>统计截止：{{ formatTime(review.content.cutoff) }}</p>
          <p v-if="!review.content.baseline">没有有效的早晨计划基准。本次不计算计划完成率。</p>
          <ul><li v-for="(item,index) in review.content.items" :key="item.key || index">{{ item.unavailable ? '原任务已不可访问' : `${item.title}：${statusLabel(item.reviewStatus)}` }}</li></ul>
          <template v-if="review.content.additionalItems?.length">
            <h3>今日新建但未纳入计划</h3>
            <ul><li v-for="(item,index) in review.content.additionalItems" :key="item.key || index">{{ item.unavailable ? '原任务已不可访问' : `${item.title}：${statusLabel(item.status)}` }}</li></ul>
          </template>
          <label for="daily-feedback">今天遇到什么阻碍？明天最想推进什么？</label>
          <textarea id="daily-feedback" v-model="feedback" maxlength="2000" rows="5" />
          <button :disabled="busy" @click="saveFeedback">保存复盘反馈</button>
        </template>
        <template v-else-if="review?.missing"><p>这一天尚未生成复盘，今日复盘在 21:00 后生成。</p><button v-if="date === today" :disabled="busy" @click="generateReview">生成今日复盘</button></template>
        <p v-else>复盘尚未加载，请刷新重试。</p>
      </section>
      <RouterLink to="/work-tasks">管理工作任务</RouterLink> · <RouterLink to="/tasks">管理家庭任务</RouterLink>
    </template>
    <p v-if="busy" role="status">正在处理，请稍候…</p>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import client from '../api/client'

// 使用北京时间，避免设备时区改变计划所属日期。
const currentDay = () => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
const today = ref(currentDay())
const date = ref(today.value)
const settings = ref(null), plan = ref(null), review = ref(null), items = ref([]), feedback = ref('')
const busy = ref(false), error = ref('')
const sourceLabel = source => ({ work: '工作', family: '家庭', calendar: '日程' }[source] || source)
const statusLabel = status => ({ PENDING: '待办', IN_PROGRESS: '进行中', COMPLETED: '已完成', CANCELLED: '已取消', DRAFT: '待确认提案', EVENT: '日程' }[status] || status)
const formatTime = value => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) : ''

async function perform(action) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await action() } catch (e) { error.value = e.message || '暂时无法加载，请重试' } finally { busy.value = false }
}
function setPlan(value) { plan.value = value; items.value = (value.content?.items || []).map(item => ({ ...item })) }
async function fetchRecords() {
  // 清空旧日期内容，加载失败时不把上次结果显示成当前日期。
  plan.value = null; review.value = null; items.value = []; feedback.value = ''
  const [p, r] = await Promise.all([client.get(`/daily/plans/${date.value}`), client.get(`/daily/reviews/${date.value}`)])
  setPlan(p); review.value = r; feedback.value = r.feedback || ''
}
function load() { return perform(async () => { const next = currentDay(); if (date.value === today.value) date.value = next; today.value = next; settings.value = await client.get('/daily/settings'); if (settings.value.available) await fetchRecords() }) }
function toggle(enabled) { return perform(async () => { settings.value = await client.put('/daily/settings', { enabled }) }) }
function move(index) { [items.value[index - 1], items.value[index]] = [items.value[index], items.value[index - 1]] }
async function savePlan() {
  const visible = items.value.filter(item => !item.unavailable)
  const focus = visible.filter(item => item.focus).map(item => item.key)
  if (focus.length > 3) throw new Error('请最多选择三项重点')
  setPlan(await client.put(`/daily/plans/${date.value}`, { version: plan.value.version, order: visible.map(item => item.key), focus }))
}
function save() { return perform(savePlan) }
function confirm() { return perform(async () => { await savePlan(); setPlan(await client.post(`/daily/plans/${date.value}/confirm`, { version: plan.value.version })) }) }
function generatePlan() { return perform(async () => { setPlan(await client.post('/daily/plans/generate')) }) }
function generateReview() { return perform(async () => { review.value = await client.post('/daily/reviews/generate'); feedback.value = review.value.feedback || '' }) }
function saveFeedback() { return perform(async () => { review.value = await client.patch(`/daily/reviews/${date.value}`, { version: review.value.version, text: feedback.value }) }) }
onMounted(load)
</script>

<style scoped>
.daily-view { max-width: 800px; margin: auto; font-size: 1.125rem; color: #1f2937; line-height: 1.6; }
section { margin: 24px 0; padding: 20px; border: 2px solid #d1d5db; border-radius: 12px; background: white; }
button, input, textarea { font: inherit; }
button { min-height: 44px; padding: 8px 16px; margin: 8px 8px 8px 0; border: 1px solid #2563eb; border-radius: 8px; background: white; color: #1d4ed8; }
button:disabled { color: #6b7280; border-color: #9ca3af; }
input[type=checkbox] { width: 24px; height: 24px; vertical-align: middle; }
textarea { display: block; width: 100%; box-sizing: border-box; padding: 12px; border: 1px solid #6b7280; border-radius: 8px; }
li { margin-bottom: 16px; }
.error { color: #991b1b; background: #fee2e2; padding: 12px; }
:focus-visible { outline: 3px solid #1d4ed8; outline-offset: 3px; }
</style>
