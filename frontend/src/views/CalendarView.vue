<template>
  <el-card class="calendar-card" role="region" aria-label="日程管理：查看今日日程、添加和修改日程">
    <template #header>
      <div class="card-header">
        <span class="title">📅 我的日程</span>
        <el-button type="primary" class="add-btn" @click="openCreate">➕ 添加日程</el-button>
      </div>
    </template>

    <el-alert
      v-if="!currentFamilyId"
      title="请先在「家庭管理」中选择一个家庭，才能管理日程"
      type="warning"
      :closable="false"
      show-icon
    />

    <template v-else>
      <!-- 今日日程：来自后端 E-6 展开结果，已按时间升序。
           失败态必须先于空态判断：接口没拉成功时照旧渲染「今天没有日程安排」，等于让老人漏掉吃药/接人 -->
      <section class="today-section" aria-label="今日日程">
        <h2 class="section-title">☀️ 今天（{{ todayLabel }}）</h2>
        <div v-if="todayError" class="today-error empty-tip">
          <span>今天日程没加载出来，请检查网络后重试</span>
          <el-button class="op-btn" type="warning" @click="loadAll">🔄 重试一下</el-button>
        </div>
        <div v-else-if="todayEvents.length === 0" class="empty-tip">今天没有日程安排，休息一下吧 😊</div>
        <ul v-else class="event-list">
          <li v-for="ev in todayEvents" :key="'today-' + ev.eventId" class="today-item">
            <span class="event-time">🕐 {{ ev.eventTime }}</span>
            <span class="event-title">{{ ev.title }}</span>
          </li>
        </ul>
      </section>

      <!-- 全部日程：重复日程只有一条记录，按规则每天生效。
           失败态同样必须先于空态（终审挂账第三处同类谎报）：接口没拉成功时若照旧渲染
           「还没有日程，点右上角【➕ 添加日程】」，等于对已有日程的家庭说「一条都没有」，
           还会诱导老人重新建一遍吃药、接孙子的提醒 -->
      <section class="list-section" v-loading="loading" aria-label="日程列表">
        <h2 class="section-title">🗓️ 全部日程</h2>
        <div v-if="listError" class="list-error empty-tip">
          <span>日程列表没加载出来，请检查网络后重试</span>
          <el-button class="op-btn" type="warning" @click="loadAll">🔄 重试一下</el-button>
        </div>
        <div v-else-if="!loading && events.length === 0" class="empty-tip">
          还没有日程，点右上角【➕ 添加日程】，把吃药、接孙子这些事定下来吧 😊
        </div>
        <ul v-else class="event-list">
          <li v-for="row in events" :key="row.id" class="event-row">
            <div class="event-main">
              <span class="event-title">{{ row.title }}</span>
              <span class="event-rule">{{ ruleText(row) }}</span>
              <span v-if="row.description" class="event-desc">📝 {{ row.description }}</span>
            </div>
            <div class="event-actions">
              <el-button class="op-btn" @click="openEdit(row)">✏️ 修改日程</el-button>
              <el-button class="op-btn" type="danger" @click="handleDelete(row)">🗑️ 删除</el-button>
            </div>
          </li>
        </ul>
      </section>
    </template>

    <el-dialog v-model="dialogVisible" :title="form.id ? '✏️ 修改日程' : '➕ 添加日程'" :width="dialogWidth">
      <el-form label-position="top" class="event-form">
        <el-form-item label="日程标题">
          <el-input v-model="form.title" size="large" maxlength="200" clearable placeholder="如：量血压、家庭聚会" />
        </el-form-item>

        <el-form-item label="什么时间">
          <el-time-picker
            v-model="form.eventTime"
            value-format="HH:mm"
            format="HH:mm"
            size="large"
            placeholder="选择时间（如 早上 08:00）"
            style="width: 100%"
            aria-label="选择日程的具体时间"
          />
        </el-form-item>

        <el-form-item label="多久一次">
          <div class="mode-grid" role="group" aria-label="选择重复频率">
            <button
              v-for="m in modes"
              :key="m.value"
              type="button"
              class="mode-btn"
              :class="{ active: form.mode === m.value }"
              @click="form.mode = m.value"
            >{{ m.label }}</button>
          </div>
        </el-form-item>

        <el-form-item v-if="form.mode === 'once'" label="选哪一天">
          <el-date-picker
            v-model="form.eventDate"
            type="date"
            value-format="YYYY-MM-DD"
            size="large"
            placeholder="选择日期"
            style="width: 100%"
            aria-label="选择日程的日期"
          />
        </el-form-item>

        <el-form-item v-if="form.mode === 'weekly'" label="每周哪几天（可以多选）">
          <div class="weekday-grid" role="group" aria-label="选择星期，可多选">
            <button
              v-for="(name, idx) in weekdayNames"
              :key="idx"
              type="button"
              class="weekday-btn"
              :class="{ active: form.weekdays.includes(idx + 1) }"
              @click="toggleWeekday(idx + 1)"
            >{{ name }}</button>
          </div>
        </el-form-item>

        <el-form-item v-if="form.mode === 'monthly'" label="每月几号">
          <el-input-number v-model="form.dayOfMonth" :min="1" :max="31" size="large" aria-label="每月几号，输入 1 到 31" />
          <span class="form-tip">号</span>
        </el-form-item>

        <el-form-item label="备注（可以不填）">
          <el-input v-model="form.description" type="textarea" :rows="2" size="large" maxlength="500" placeholder="如：饭后半小时" />
        </el-form-item>

        <el-form-item label="微信早报提醒">
          <el-switch v-model="form.reminderEnabled" size="large" active-text="提醒" inactive-text="不提醒" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button size="large" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" size="large" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { calendarApi } from '../api/calendar'
import { useFamilyStore } from '../stores/family'
import { useIsMobile } from '../composables/useIsMobile.js'

const familyStore = useFamilyStore()
const currentFamilyId = computed(() => familyStore.currentFamilyId)

/** 星期名，下标 0~6 对应 ISO 星期 1~7（1=周一 … 7=周日），与后端 CalendarRecurrenceMatcher 口径一致 */
const weekdayNames = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
/** 重复频率选项（大按钮单选，老人一眼能看懂） */
const modes = [
  { value: 'once', label: '不重复' },
  { value: 'daily', label: '每天' },
  { value: 'weekly', label: '每周' },
  { value: 'monthly', label: '每月' }
]

const events = ref([])
const todayEvents = ref([])
// 今日日程是否加载失败：与「今天确实没事」分开，失败时整块切到可重试的失败态（终审挂账）
const todayError = ref(false)
// 全部日程列表是否加载失败：与「家里一条日程都没有」分开，同一判据（终审挂账第三处）
const listError = ref(false)
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)

const isMobile = useIsMobile()
/**
 * 弹层宽度：el-dialog 默认 teleport 到 body，scoped 样式穿不透其内部节点，
 * 故用 :width 动态绑定实现响应式——移动端 94%（硬约束 2：老人易用，小屏不挤爆），
 * 桌面 640px。断点 768px 与 composables/useIsMobile.js 共用同一 SSOT。
 */
const dialogWidth = computed(() => (isMobile.value ? '94%' : '640px'))

/**
 * 「今天」时间戳：与今日日程同一数据源——每次 loadAll 刷新一次（Task7 M-4）。
 * 若直接用 new Date() 包在 computed 里，computed 无任何响应式依赖，
 * 首次求值后永久缓存：页面跨零点继续开着，标题会停在昨天，与后端返回的今日列表对不上。
 */
const todayStamp = ref(new Date())

/** 今天日期展示文案，如 2026-09-10 周四 */
const todayLabel = computed(() => {
  const d = todayStamp.value
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} 周${'日一二三四五六'[d.getDay()]}`
})

const form = reactive({
  id: null,
  title: '',
  description: '',
  eventTime: '',
  mode: 'once',
  eventDate: '',
  weekdays: [],
  dayOfMonth: null,
  reminderEnabled: true
})

function resetForm() {
  form.id = null
  form.title = ''
  form.description = ''
  form.eventTime = ''
  form.mode = 'once'
  // 默认选今天：老人改一个数字就能保存，少一步操作
  form.eventDate = todayLabel.value.split(' ')[0]
  form.weekdays = []
  form.dayOfMonth = null
  form.reminderEnabled = true
}

/** 解析后端返回的 recurrence JSON 字符串；无效或为空按「不重复」处理 */
function parseRecurrence(raw) {
  if (!raw) return null
  try {
    const obj = JSON.parse(raw)
    return obj && typeof obj === 'object' ? obj : null
  } catch (e) {
    return null
  }
}

/** 列表里的重复规则中文文案（老人可读） */
function ruleText(row) {
  const rec = parseRecurrence(row.recurrence)
  if (!rec) {
    return row.eventDate ? `📅 ${row.eventDate}（就这一天）` : '📅 未定日期'
  }
  if (rec.freq === 'daily') return '🔁 每天'
  if (rec.freq === 'weekly') {
    const days = (rec.weekdays || []).map((w) => weekdayNames[w - 1]).join('、')
    return `🔁 每周 ${days || '（还没选星期）'}`
  }
  if (rec.freq === 'monthly') return `🔁 每月 ${rec.day} 号`
  return '🔁 重复日程'
}

/**
 * 列表与今日两个区块一起刷新，某一侧失败不连带另一侧（这是 Promise.allSettled 的本意）。
 * 注释订正（终审挂账）：原注释写「今日失败可静默」是假的——client.js 响应拦截器对两侧失败
 * 都会先弹一次红色 toast，页面再保持静默只会把「没拉到」渲染成「今天没有日程安排」/「还没有日程」。
 * 所以两侧失败都显式切各自的失败态；刻意清空旧值，避免把上一次成功结果当成当前的最新安排。
 */
async function loadAll() {
  if (!currentFamilyId.value) return
  // 先对齐「今天」，再发请求：标题文案与后端展开的今日列表同源同一天
  todayStamp.value = new Date()
  loading.value = true
  try {
    const [listResult, todayResult] = await Promise.allSettled([calendarApi.list(), calendarApi.today()])
    if (listResult.status === 'fulfilled') {
      events.value = listResult.value || []
      listError.value = false
    } else {
      events.value = []
      listError.value = true
    }
    if (todayResult.status === 'fulfilled') {
      todayEvents.value = todayResult.value || []
      todayError.value = false
    } else {
      todayEvents.value = []
      todayError.value = true
    }
  } finally {
    loading.value = false
  }
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(row) {
  form.id = row.id
  form.title = row.title || ''
  form.description = row.description || ''
  form.eventTime = row.eventTime || ''
  form.reminderEnabled = row.reminderEnabled !== false
  const rec = parseRecurrence(row.recurrence)
  // 三个维度（日期 / 星期 / 几号）先统一重置再按规则赋值：
  // 弹层复用同一个 form，不重置的话改完一条「每周」再开「每天」，
  // 用户手上没有星期可选，但残值仍会被带进保存结果（Task7 M-3）
  form.eventDate = row.eventDate || ''
  form.weekdays = []
  form.dayOfMonth = null
  if (!rec) {
    form.mode = 'once'
  } else if (rec.freq === 'daily') {
    form.mode = 'daily'
  } else if (rec.freq === 'weekly') {
    form.mode = 'weekly'
    form.weekdays = Array.isArray(rec.weekdays) ? [...rec.weekdays] : []
  } else if (rec.freq === 'monthly') {
    form.mode = 'monthly'
    form.dayOfMonth = typeof rec.day === 'number' ? rec.day : null
  } else {
    form.mode = 'once'
  }
  dialogVisible.value = true
}

function toggleWeekday(isoDay) {
  const idx = form.weekdays.indexOf(isoDay)
  if (idx >= 0) form.weekdays.splice(idx, 1)
  else form.weekdays.push(isoDay)
}

/** 本地校验：返回第一条中文错误提示，全部通过返回空串 */
function validateForm() {
  if (!form.title.trim()) return '请填写日程标题，比如「量血压」'
  if (!form.eventTime) return '请选择日程的时间（几点）'
  if (form.mode === 'once' && !form.eventDate) {
    return '请选择日程日期，或者选择重复规则（每天 / 每周 / 每月）'
  }
  if (form.mode === 'weekly' && form.weekdays.length === 0) return '请至少选择一个星期（如 周一、周三）'
  if (form.mode === 'monthly' && (!form.dayOfMonth || form.dayOfMonth < 1 || form.dayOfMonth > 31)) {
    return '请填写每月几号，数字 1 到 31'
  }
  return ''
}

/**
 * 组装请求体：与后端契约对齐 —— eventDate（YYYY-MM-DD）与 recurrence（JSON 字符串）二选一。
 * update 是 PATCH 语义：传 eventDate 即清重复规则、传 recurrence 即清日期，
 * 所以重复模式下绝不能带上 eventDate。
 */
function buildPayload() {
  const payload = {
    title: form.title.trim(),
    // 备注恒传（清空时传空串）：后端 update 对 description 是「非 null 才覆盖」，
    // 传 undefined 等于不提交该字段，用户删掉的备注将永远清不掉（Task7 M-1）
    description: form.description.trim(),
    eventTime: form.eventTime,
    reminderEnabled: form.reminderEnabled
  }
  if (form.mode === 'once') {
    payload.eventDate = form.eventDate
  } else if (form.mode === 'daily') {
    payload.recurrence = JSON.stringify({ freq: 'daily' })
  } else if (form.mode === 'weekly') {
    payload.recurrence = JSON.stringify({ freq: 'weekly', weekdays: [...form.weekdays].sort((a, b) => a - b) })
  } else if (form.mode === 'monthly') {
    payload.recurrence = JSON.stringify({ freq: 'monthly', day: Number(form.dayOfMonth) })
  }
  return payload
}

async function handleSave() {
  const err = validateForm()
  if (err) {
    ElMessage.warning(err)
    return
  }
  saving.value = true
  try {
    const payload = buildPayload()
    if (form.id) await calendarApi.update(form.id, payload)
    else await calendarApi.create(payload)
    ElMessage.success('日程已保存 ✅')
    dialogVisible.value = false
    await loadAll()
  } catch (e) {
    // 后端业务失败（如 47003/47004）已由 client.js 拦截器统一中文提示，这里只需收尾不再重复报错
  } finally {
    saving.value = false
  }
}

async function handleDelete(row) {
  if (!row) return
  try {
    await ElMessageBox.confirm(
      `确定要删除日程「${row.title || ''}」吗？删除后就不再提醒了。`,
      '🗑️ 删除日程确认',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (e) {
    return // 用户点了取消
  }
  try {
    await calendarApi.del(row.id)
    ElMessage.success('日程已删除')
    await loadAll()
  } catch (e) {
    // 删除失败的中文提示已由拦截器统一弹出
  }
}

onMounted(loadAll)
</script>

<style scoped>
.calendar-card {
  max-width: 960px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
.card-header .title {
  font-size: 24px;
  font-weight: 700;
  color: #17222f;
}
.add-btn {
  min-height: 56px;
  font-size: 20px;
  font-weight: 700;
  padding: 0 28px;
}
.section-title {
  font-size: 22px;
  font-weight: 700;
  color: #17222f;
  margin: 18px 0 10px;
}
.today-section,
.list-section {
  min-height: 60px;
}
.event-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.today-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 14px;
  border-bottom: 1px solid #e6e8eb;
}
/* 今日/全部两区块失败态同形复用：文案与重试按钮竖排，按钮沿用 .op-btn 的大点击区（硬约束 2：报错必须给下一步） */
.today-error,
.list-error {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 12px;
}
.event-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  padding: 14px;
  border: 1px solid #e6e8eb;
  border-radius: 10px;
  margin-bottom: 12px;
  background: #fafbfc;
}
.event-main {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
/* 正文一律 18px 起步（硬约束 2：老人易用） */
.event-title {
  font-size: 20px;
  font-weight: 700;
  color: #17222f;
}
.event-time {
  font-size: 18px;
  font-weight: 700;
  color: #b3541e;
  min-width: 92px;
}
.event-rule {
  font-size: 18px;
  color: #33475b;
}
.event-desc {
  font-size: 18px;
  color: #5c6b7a;
}
.empty-tip {
  font-size: 19px;
  color: #5c6b7a;
  padding: 18px 4px;
}
.event-actions {
  display: flex;
  gap: 10px;
}
.op-btn {
  min-height: 56px;
  font-size: 18px;
  font-weight: 700;
  padding: 0 22px;
}
/* 重复频率与星期：大按钮点选，选中态用蓝底白字强对比 */
.mode-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  width: 100%;
}
.mode-btn,
.weekday-btn {
  min-height: 56px;
  font-size: 19px;
  font-weight: 700;
  color: #17222f;
  background: #ffffff;
  border: 2px solid #c0c6cf;
  border-radius: 12px;
  cursor: pointer;
}
.weekday-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 8px;
  width: 100%;
}
.weekday-btn {
  font-size: 17px;
}
.mode-btn.active,
.weekday-btn.active {
  background: #2f6fed;
  border-color: #2f6fed;
  color: #ffffff;
}
.form-tip {
  font-size: 18px;
  color: #33475b;
  margin-left: 10px;
}
.event-form :deep(.el-form-item__label) {
  font-size: 19px;
  font-weight: 700;
  color: #17222f;
}
/* 移动端：按钮网格改为两列/四列，避免小屏挤成一团 */
@media (max-width: 768px) {
  .mode-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .weekday-grid {
    grid-template-columns: repeat(4, 1fr);
  }
}
</style>
