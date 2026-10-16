// Change 13 Task 8（账本清偿②）：CalendarView 挂载冒烟 + Task 7 审查挂账回归守卫
// 覆盖：挂载零 console.warn（防 Task7 I-1 类模板绑定缺陷回归）、今日/全部两区块渲染、
//       「添加日程」打开弹层、M-1 description 恒传、M-2「已停用」死分支已删、
//       M-3 openEdit 重复维度残值重置、M-4 todayLabel 与数据同 refresh、
//       今日/全部两区块加载失败态（终审挂账：IMP-3 同类谎报，失败不得渲染成「今天没有日程安排」/「还没有日程」）
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'

// vi.mock 工厂会被提升到文件顶部：mock 实现只写 vi.fn()，数据一律在 beforeEach 注入（避免 TDZ）
vi.mock('@/api/calendar', () => ({
  calendarApi: {
    list: vi.fn(),
    create: vi.fn(),
    get: vi.fn(),
    update: vi.fn(),
    del: vi.fn(),
    today: vi.fn()
  }
}))

import { calendarApi } from '@/api/calendar'
import CalendarView from '@/views/CalendarView.vue'

/**
 * 挂载：stubs.teleport 把 el-dialog / 日期选择器等 teleport 内容就地渲染。
 * happy-dom 下 el-dialog 默认 teleport 到 body，wrapper 选择器穿不透；
 * 桩掉 teleport 后所有断言统一走 wrapper，无需依赖 document.body。
 */
async function mountView() {
  const wrapper = mount(CalendarView, {
    global: { plugins: [createPinia()], stubs: { teleport: true } }
  })
  await flushPromises()
  return wrapper
}

/** 每行两个 op-btn（修改 / 删除），故第 n 行的「修改」下标为 n*2 */
function editBtn(wrapper, rowIndex) {
  return wrapper.findAll('.op-btn')[rowIndex * 2]
}

/** 按中文文案点击大按钮（模式选择、弹层底部按钮通用） */
async function clickByText(wrapper, selector, text) {
  const target = wrapper.findAll(selector).find((el) => el.text().includes(text))
  if (!target) throw new Error(`未找到元素：${selector} 含文案「${text}」`)
  await target.trigger('click')
  await flushPromises()
}

let warnSpy

beforeEach(() => {
  vi.mocked(calendarApi.list).mockResolvedValue([
    {
      id: 1, title: '吃降压药', description: null, eventDate: '2026-09-15', eventTime: '08:00',
      recurrence: null, reminderEnabled: true, assigneeProfileId: null, categoryId: null, isActive: true
    },
    {
      id: 2, title: '每周复查', description: '空腹', eventDate: null, eventTime: '09:30',
      recurrence: '{"freq":"weekly","weekdays":[2,4]}', reminderEnabled: true,
      assigneeProfileId: null, categoryId: null, isActive: true
    },
    {
      id: 3, title: '每天散步', description: null, eventDate: null, eventTime: '19:00',
      recurrence: '{"freq":"daily"}', reminderEnabled: true,
      assigneeProfileId: null, categoryId: null, isActive: true
    }
  ])
  vi.mocked(calendarApi.today).mockResolvedValue([
    { eventId: 1, title: '吃降压药', eventTime: '08:00', assigneeProfileId: null },
    { eventId: 3, title: '每天散步', eventTime: '19:00', assigneeProfileId: null }
  ])
  vi.mocked(calendarApi.create).mockResolvedValue({})
  vi.mocked(calendarApi.update).mockResolvedValue({})
  vi.mocked(calendarApi.del).mockResolvedValue({})
  localStorage.setItem('currentFamilyId', '7')
  // useIsMobile 依赖 matchMedia，固定桌面分支（弹层宽度非本测试关注点）
  window.matchMedia = vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} }))
  warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
})

afterEach(() => {
  localStorage.removeItem('currentFamilyId')
  // 不用 vi.restoreAllMocks() 之外的整体还原：mockResolvedValue 会被清空（TaskViewMobile.test.js 教训）
  vi.clearAllMocks()
  vi.useRealTimers()
})

describe('CalendarView 挂载冒烟（账本②）', () => {
  it('用例1: 挂载零 console.warn，今日区块与全部日程区块均渲染', async () => {
    const wrapper = await mountView()
    // Task7 I-1 教训：模板里引用未定义的绑定会让 Vue 打 warn，静默丢渲染
    expect(warnSpy).not.toHaveBeenCalled()
    expect(wrapper.find('.today-section').exists()).toBe(true)
    expect(wrapper.find('.list-section').exists()).toBe(true)
    expect(wrapper.text()).toContain('全部日程')
    expect(wrapper.findAll('.today-item')).toHaveLength(2)
    expect(wrapper.findAll('.event-row')).toHaveLength(3)
    expect(wrapper.text()).toContain('吃降压药')
    expect(wrapper.text()).toContain('🔁 每周 周二、周四')
  })

  it('用例2: 今日无日程时显示空态文案', async () => {
    vi.mocked(calendarApi.today).mockResolvedValue([])
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('今天没有日程安排')
  })

  // 终审挂账（IMP-3 同类谎报）：loadAll 用 allSettled，today() 失败时数组留在 [] 上，
  // 页面照旧渲染「今天没有日程安排，休息一下吧」——修复前该断言必然失败。
  it('用例2b: today() 接口失败 -> 今日区块显示失败态，不得谎报「今天没有日程安排」', async () => {
    vi.mocked(calendarApi.today).mockRejectedValue(new Error('网络不可用'))
    const wrapper = await mountView()
    expect(wrapper.find('.today-error').exists()).toBe(true)
    expect(wrapper.text()).toContain('今天日程没加载出来')
    expect(wrapper.text()).not.toContain('今天没有日程安排')
    // 全部日程区块独立成功，不能被今日区块失败连带降级
    expect(wrapper.findAll('.event-row')).toHaveLength(3)
  })

  it('用例2c: 今日区块失败后点重试，成功即恢复事件列表并清掉失败态', async () => {
    vi.mocked(calendarApi.today)
      .mockRejectedValueOnce(new Error('网络不可用'))
      .mockResolvedValueOnce([
        { eventId: 1, title: '吃降压药', eventTime: '08:00', assigneeProfileId: null }
      ])
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('今天日程没加载出来')

    await wrapper.find('.today-error button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.today-error').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('今天日程没加载出来')
    expect(wrapper.findAll('.today-item')).toHaveLength(1)
    // 重试走整页刷新，全部日程区块同步再拉一次
    expect(calendarApi.list.mock.calls.length).toBeGreaterThanOrEqual(2)
  })

  // 终审挂账（第三处同类谎报）：list() 失败时 events 留在 []，页面照旧渲染
  // 「还没有日程，点右上角【➕ 添加日程】」——既谎报「一条都没有」，又诱导老人重复建吃药提醒。
  it('用例2d: list() 接口失败 -> 全部日程区块显示失败态，不得谎报「还没有日程」', async () => {
    vi.mocked(calendarApi.list).mockRejectedValue(new Error('网络不可用'))
    const wrapper = await mountView()
    expect(wrapper.find('.list-error').exists()).toBe(true)
    expect(wrapper.text()).toContain('日程列表没加载出来')
    expect(wrapper.text()).not.toContain('还没有日程')
    // 今日区块独立成功，不被列表区块失败连带降级（allSettled 的本意）
    expect(wrapper.findAll('.today-item')).toHaveLength(2)
  })

  it('用例2e: 全部日程失败后点重试，成功即恢复列表并清掉失败态', async () => {
    vi.mocked(calendarApi.list)
      .mockRejectedValueOnce(new Error('网络不可用'))
      .mockResolvedValueOnce([
        {
          id: 5, title: '每天散步', description: null, eventDate: null, eventTime: '19:00',
          recurrence: '{"freq":"daily"}', reminderEnabled: true,
          assigneeProfileId: null, categoryId: null, isActive: true
        }
      ])
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('日程列表没加载出来')

    await wrapper.find('.list-error button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.list-error').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('日程列表没加载出来')
    expect(wrapper.findAll('.event-row')).toHaveLength(1)
  })

  it('用例3: 未选家庭整页降级；点「添加日程」打开弹层', async () => {
    localStorage.removeItem('currentFamilyId')
    const alertWrapper = await mountView()
    expect(alertWrapper.find('.el-alert').exists()).toBe(true)
    expect(alertWrapper.find('.add-btn').exists()).toBe(true)

    localStorage.setItem('currentFamilyId', '7')
    const wrapper = await mountView()
    expect(wrapper.find('.el-dialog').exists()).toBe(false)
    await wrapper.find('.add-btn').trigger('click')
    await flushPromises()
    expect(wrapper.find('.el-dialog').exists()).toBe(true)
    expect(wrapper.find('.el-dialog').text()).toContain('日程标题')
  })
})

describe('Task 7 审查挂账回归（账本③④⑤⑥）', () => {
  it('用例4: M-1 备注为空时 payload 仍恒传 description 空串，update 能清空既有备注', async () => {
    const wrapper = await mountView()
    await editBtn(wrapper, 0).trigger('click') // 修改「吃降压药」（description 为 null）
    await flushPromises()
    await clickByText(wrapper, '.el-dialog .el-button', '保存')
    expect(calendarApi.update).toHaveBeenCalledTimes(1)
    const payload = calendarApi.update.mock.calls[0][1]
    expect('description' in payload).toBe(true)
    expect(payload.description).toBe('')
    expect(payload.eventDate).toBe('2026-09-15')
    expect(payload.recurrence).toBeUndefined()
  })

  it('用例5: M-2 列表不再渲染「已停用」标签（后端无停用路径，死分支已删）', async () => {
    vi.mocked(calendarApi.list).mockResolvedValue([
      {
        id: 9, title: '历史停用日程', description: null, eventDate: '2026-09-20', eventTime: '07:00',
        recurrence: null, reminderEnabled: true, assigneeProfileId: null, categoryId: null, isActive: false
      }
    ])
    const wrapper = await mountView()
    expect(wrapper.findAll('.event-row')).toHaveLength(1)
    expect(wrapper.text()).toContain('历史停用日程')
    expect(wrapper.text()).not.toContain('已停用')
  })

  it('用例6: M-3 从每周日程切到每天日程后重新选「每周」，星期不再残留上一日程的选中值', async () => {
    const wrapper = await mountView()
    await editBtn(wrapper, 1).trigger('click') // 修改「每周复查」weekdays=[2,4]
    await flushPromises()
    expect(wrapper.findAll('.weekday-btn.active')).toHaveLength(2)

    await editBtn(wrapper, 2).trigger('click') // 直接改开「每天散步」（daily）
    await flushPromises()
    await clickByText(wrapper, '.mode-btn', '每周')
    expect(wrapper.findAll('.weekday-btn.active')).toHaveLength(0)
  })

  it('用例7: M-4 todayLabel 随每次刷新更新，跨天重新加载不会停留在旧日期', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-09-15T08:00:00'))
    const wrapper = await mountView()
    expect(wrapper.find('.today-section .section-title').text()).toContain('2026-09-15 周二')

    vi.setSystemTime(new Date('2026-09-16T08:00:00'))
    await editBtn(wrapper, 0).trigger('click')
    await flushPromises()
    // 保存成功后 loadAll 重跑，日期文案应跟随后端「今日」同一天
    await clickByText(wrapper, '.el-dialog .el-button', '保存')
    expect(wrapper.find('.today-section .section-title').text()).toContain('2026-09-16 周三')
  })
})
