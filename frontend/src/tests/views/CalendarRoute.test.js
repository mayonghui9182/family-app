// Change 13 Task 8：日历日程前端集成冒烟测试
// 覆盖：/calendar 路由（懒加载 + meta 标题图标）、更多菜单入口、侧边栏菜单项、
//       DashboardView 今日日程卡片（有数据 / 空态 / 接口失败进可重试失败态）、TaskCardMobile 📅 日程角标、
//       DashboardView 最近任务表格失败态（IMP-3 同类谎报清偿，终审挂账）、
//       DashboardView 顶部指标卡（终审挂账：指标未就绪/加载失败不得渲染 NaN）
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

// vi.mock 工厂会被提升：mock 实现必须全部写在工厂内部（模块级常量会 TDZ）
vi.mock('@/api/tasks', () => ({
  tasksApi: { page: vi.fn().mockResolvedValue({ items: [] }), stats: vi.fn().mockResolvedValue({}) }
}))
vi.mock('@/api/calendar', () => ({
  calendarApi: {
    list: vi.fn().mockResolvedValue([]),
    create: vi.fn().mockResolvedValue({}),
    get: vi.fn().mockResolvedValue({}),
    update: vi.fn().mockResolvedValue({}),
    del: vi.fn().mockResolvedValue({}),
    today: vi.fn().mockResolvedValue([])
  }
}))
// 指标卡数据源：stores/summary.js 经此 SDK 取 C-34 三指标，桩掉以免打到 axios 真实请求
vi.mock('@/api/sdkSummary', () => ({
  summaryApi: {
    trigger: vi.fn().mockResolvedValue({}),
    list: vi.fn().mockResolvedValue([]),
    metrics: vi.fn().mockResolvedValue(null)
  }
}))

import { calendarApi } from '@/api/calendar'
import { tasksApi } from '@/api/tasks'
import { summaryApi } from '@/api/sdkSummary'
import MoreMenuView from '@/views/MoreMenuView.vue'
import AppSider from '@/components/AppSider.vue'
import DashboardView from '@/views/DashboardView.vue'
import TaskCardMobile from '@/components/TaskCardMobile.vue'

beforeEach(() => {
  // 恢复每个 api mock 的默认成功实现（不用 restoreAllMocks，参照 TaskViewMobile 教训）
  vi.mocked(calendarApi.today).mockResolvedValue([])
  vi.mocked(calendarApi.list).mockResolvedValue([])
  vi.mocked(tasksApi.page).mockResolvedValue({ items: [] })
})

describe('/calendar 路由注册（Task 8 Step 1/3）', () => {
  it('用例1: 路由表含 /calendar，组件懒加载，meta 标题「日程」+ 图标 📅', async () => {
    const router = (await import('@/router')).default
    const route = router.getRoutes().find((r) => r.name === 'calendar')
    expect(route).toBeDefined()
    expect(route.path).toBe('/calendar')
    expect(typeof (route.components?.default ?? route.component)).toBe('function')
    expect(route.meta.title).toBe('日程')
    expect(route.meta.icon).toBe('📅')
  })
})

async function mountMoreMenu() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/more', component: MoreMenuView },
      { path: '/templates', component: { template: '<div/>' } },
      { path: '/automation', component: { template: '<div/>' } },
      { path: '/categories', component: { template: '<div/>' } },
      { path: '/families', component: { template: '<div/>' } },
      { path: '/calendar', component: { template: '<div/>' } }
    ]
  })
  router.push('/more')
  await router.isReady()
  const wrapper = mount(MoreMenuView, { global: { plugins: [router] } })
  return { wrapper, router }
}

describe('MoreMenuView 日程入口（Task 8 Step 3）', () => {
  it('用例2: entries 含「日程」入口卡片（📅 + /calendar 可跳转）', async () => {
    const { wrapper, router } = await mountMoreMenu()
    const cards = wrapper.findAll('.more-card')
    expect(cards.length).toBeGreaterThanOrEqual(1)
    const calendarCard = cards.find((c) => c.text().includes('日程'))
    expect(calendarCard).toBeTruthy()
    expect(calendarCard.text()).toContain('📅')
    await calendarCard.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/calendar')
  })
})

async function mountSider() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div/>' } },
      { path: '/dashboard', component: { template: '<div/>' } },
      { path: '/tasks', component: { template: '<div/>' } },
      { path: '/chat', component: { template: '<div/>' } },
      { path: '/templates', component: { template: '<div/>' } },
      { path: '/automation', component: { template: '<div/>' } },
      { path: '/categories', component: { template: '<div/>' } },
      { path: '/families', component: { template: '<div/>' } },
      { path: '/calendar', component: { template: '<div/>' } }
    ]
  })
  router.push('/dashboard')
  await router.isReady()
  return mount(AppSider, { global: { plugins: [router] } })
}

describe('AppSider 日程菜单项（Task 8 Step 3）', () => {
  it('用例3: 侧边栏渲染「📅 日程」菜单项（共 10 项）', async () => {
    const wrapper = await mountSider()
    const items = wrapper.findAll('.el-menu-item')
    expect(items).toHaveLength(10)
    const calendarItem = items.find((el) => el.text().includes('日程'))
    expect(calendarItem).toBeTruthy()
    expect(calendarItem.text()).toContain('📅')
  })
})

async function mountDashboard() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div/>' } },
      { path: '/dashboard', component: DashboardView },
      { path: '/chat', component: { template: '<div/>' } },
      { path: '/automation', component: { template: '<div/>' } }
    ]
  })
  router.push('/dashboard')
  await router.isReady()
  const wrapper = mount(DashboardView, { global: { plugins: [router, createPinia()] } })
  await flushPromises()
  return wrapper
}

describe('DashboardView 今日日程卡片（Task 8 Step 4）', () => {
  it('用例4: calendarApi.today() 有数据 -> 卡片渲染事件时间与标题', async () => {
    vi.mocked(calendarApi.today).mockResolvedValue([
      { eventId: 11, title: '吃降压药', eventTime: '08:00' },
      { eventId: 12, title: '接孙子', eventTime: '15:30' }
    ])
    const wrapper = await mountDashboard()
    expect(wrapper.text()).toContain('今日日程')
    expect(wrapper.find('.event-time').text()).toContain('08:00')
    expect(wrapper.text()).toContain('吃降压药')
    expect(wrapper.text()).toContain('接孙子')
  })

  it('用例5: 今日无日程 -> 空态文案「今天没有日程安排」', async () => {
    vi.mocked(calendarApi.today).mockResolvedValue([])
    const wrapper = await mountDashboard()
    expect(wrapper.text()).toContain('今天没有日程安排')
  })

  // Change 13 终审 IMP-3：修复前失败也渲染「今天没有日程安排」，与「今天真的没事」完全同形。
  // 对提醒类产品这是最危险的失效形态——降压药/接孙子没拉下来，屏幕却断言今天空闲。
  it('用例6: today() 接口失败 -> 显示「日程没加载出来」，不得谎报「今天没有日程安排」', async () => {
    vi.mocked(calendarApi.today).mockRejectedValue(new Error('网络不可用'))
    const wrapper = await mountDashboard()
    expect(wrapper.text()).toContain('今日日程')
    expect(wrapper.text()).toContain('日程没加载出来')
    expect(wrapper.text()).not.toContain('今天没有日程安排')
  })

  it('用例6b: 失败后点「重试一下」会重新拉取，成功即渲染事件列表', async () => {
    const callsBefore = calendarApi.today.mock.calls.length
    vi.mocked(calendarApi.today)
      .mockRejectedValueOnce(new Error('网络不可用'))
      .mockResolvedValueOnce([{ eventId: 11, title: '吃降压药', eventTime: '08:00' }])
    const wrapper = await mountDashboard()
    expect(wrapper.text()).toContain('日程没加载出来')

    await wrapper.find('.event-error button').trigger('click')
    await flushPromises()

    expect(calendarApi.today.mock.calls.length).toBe(callsBefore + 2)
    expect(wrapper.text()).not.toContain('日程没加载出来')
    expect(wrapper.text()).toContain('吃降压药')
  })

  // 终审挂账（IMP-3 同类谎报）：修复前 tasksApi.page() 失败被 catch 静默吞掉，
  // recentTasks 留在 [] 上，el-table 渲染 empty-text「暂无任务」——把「没拉到」说成「家里没活儿」。
  it('用例6c: tasksApi.page() 接口失败 -> 显示「任务没加载出来」+ 重试，不得谎报「暂无任务」', async () => {
    vi.mocked(tasksApi.page).mockRejectedValue(new Error('网络不可用'))
    const wrapper = await mountDashboard()
    expect(wrapper.text()).toContain('最近任务')
    expect(wrapper.text()).toContain('任务没加载出来')
    expect(wrapper.text()).not.toContain('暂无任务')
  })

  it('用例6d: 最近任务失败后点重试会重新拉取，成功即恢复表格', async () => {
    const callsBefore = tasksApi.page.mock.calls.length
    vi.mocked(tasksApi.page)
      .mockRejectedValueOnce(new Error('网络不可用'))
      .mockResolvedValueOnce({ items: [{ id: 1, title: '买米', status: 'PENDING', priority: 'HIGH' }] })
    const wrapper = await mountDashboard()
    expect(wrapper.text()).toContain('任务没加载出来')

    await wrapper.find('.task-error button').trigger('click')
    await flushPromises()

    expect(tasksApi.page.mock.calls.length).toBe(callsBefore + 2)
    expect(wrapper.text()).not.toContain('任务没加载出来')
    expect(wrapper.text()).toContain('买米')
  })
})

describe('DashboardView 顶部指标卡（终审挂账：指标未就绪不得渲染 NaN）', () => {
  beforeEach(() => {
    vi.mocked(summaryApi.metrics).mockResolvedValue(null)
    localStorage.setItem('currentFamilyId', '7')
  })
  afterEach(() => {
    localStorage.removeItem('currentFamilyId')
  })

  // 修复前两处叠加：onMounted 的 try/catch 缺 await 捕不到异步失败，且百分支路把占位串
  // '--' 送进 Math.round(Number(...)) → 首页顶部显示「NaN%」。失败要如实留占位，不能是坏屏。
  it('用例8: metrics 接口失败 -> 指标卡保持占位 -- ，全页不得出现 NaN', async () => {
    vi.mocked(summaryApi.metrics).mockRejectedValue(new Error('网络不可用'))
    const wrapper = await mountDashboard()
    expect(summaryApi.metrics).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).not.toContain('NaN')
    expect(wrapper.text()).toContain('--')
  })

  it('用例8b: metrics 加载成功 -> 首页渲染真实成功率 82%', async () => {
    vi.mocked(summaryApi.metrics).mockResolvedValue({
      success_rate_30d: 82.4, version_count_30d: 5, learned_adjustments_30d: 3
    })
    const wrapper = await mountDashboard()
    expect(wrapper.text()).toContain('82')
    expect(wrapper.text()).not.toContain('NaN')
  })
})

describe('TaskCardMobile 日程角标（Task 8 Step 5）', () => {
  it('用例7a: task.source === "CALENDAR" 显示「📅 日程」角标', () => {
    const wrapper = mount(TaskCardMobile, {
      props: { task: { id: 1, title: '量血压', status: 'PENDING', source: 'CALENDAR' } }
    })
    expect(wrapper.find('.m-meta').text()).toContain('📅 日程')
  })

  it('用例7b: 非日程来源（MANUAL）不显示角标', () => {
    const wrapper = mount(TaskCardMobile, {
      props: { task: { id: 2, title: '买菜', status: 'PENDING', source: 'MANUAL' } }
    })
    expect(wrapper.find('.m-meta').text()).not.toContain('📅 日程')
  })
})
