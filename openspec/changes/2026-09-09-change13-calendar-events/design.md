# Design: Change 13 - 日程/日历能力

## Context

Change 12 已建微信模板消息三事件（task_digest / summary_ready / task_assigned）。本 change 在任务模型之上新增独立日程实体，解决「带时间点 + 重复规则」的家庭事项建模问题，并把日程到期自动转化为任务复用现有流。

约束：5 值任务状态机不变；所有业务查询带 family_id；前端老人易用；H2 测试不支持 partial index，生产 PG 专用 DDL 放 afterMigrate 回调；微信推送异常全链路吞掉。

## Decisions

### D1：日程与任务的关系——单向生成，不双向同步

- 日程是「模板」，每日 02:30 调度器把当日命中的日程生成一条 PENDING 任务（source=CALENDAR，due_date=今日，due_time=日程时间点，source_event_id=日程 ID）。
- 生成后任务独立演化：用户改任务状态/标题不回写日程；改日程也不追溯已生成的历史任务（只影响未来生成）。
- 理由：双向同步复杂度高且老人场景不需要；单向生成让日程是「规则」、任务是「当日实例」，心智模型清晰。

### D2：重复规则模型——JSONB + freq 白名单

- `recurrence` 字段为 JSONB，三种形态：
  - `{"freq":"daily"}`
  - `{"freq":"weekly","weekdays":[2,4]}`（1=周一 … 7=周日，ISO 8601）
  - `{"freq":"monthly","day":15}`
- 一次性日程 `recurrence` 为 SQL NULL，`event_date` 非空；两者互斥由 CHECK 约束 `chk_ce_date_recurrence` 保证。
- 写路径（create/update）额外做 `validateRecurrenceValues`：freq 必须是 daily/weekly/monthly 之一；monthly.day 为 1..31 整数；weekly.weekdays 非空且元素为 1..7 整数。校验失败抛 ResultException（47004 中文消息）。
- 读路径保持前向兼容：旧数据或未知 freq 静默不命中（不抛异常）。

### D3：monthly 命中语义——月末钳制（刻意偏离 RFC 5545）

- 命中日 = `min(day, 当月天数)`。例如 `day=31` 在 2 月命中 28/29 号，在 4 月命中 30 号。
- 刻意不采用 RFC 5545 的「跳过非存在日」语义——老人场景下「每月 31 号交房租」若 2 月跳过会漏提醒，钳制到月末更符合直觉。
- `day <= 0` 恒不命中（防脏数据）。
- 决策来源与理由写进 `CalendarRecurrenceMatcher.monthlyHit` 注释。

### D4：幂等——partial unique index + catch 吞异常

- 生产 PG 建 `uq_tasks_event_date`：`UNIQUE (source_event_id, due_date) WHERE source_event_id IS NOT NULL AND deleted_at IS NULL`。
- 调度器 `createTaskFromEvent` 外层 catch `DataAccessException`，命中唯一冲突即视为「今日已生成」，log.debug 跳过。
- 放在 afterMigrate 回调而非 V11.sql：H2 不支持 partial index，V11.sql 需在 H2 测试中可跑。
- 测试覆盖：H2 上建等效非 partial 唯一索引验证 DB 层挡重复（`CalendarTaskIdempotencyTest`）+ 静态检查 afterMigrate DDL 含 WHERE 条件 + Mockito 验证 catch 吞异常（`CalendarSchedulerJobsTest` 用例 9）。

### D5：调度器——逐家庭隔离 + 注入时钟

- `CalendarSchedulerJobs.generateTodayTasks`：`@Scheduled(cron="0 30 2 * * ?")`，遍历有启用日程的家庭，单家庭 try/catch 隔离。
- 时钟经 `Clock` 注入（复用 `ClockConfig` bean），测试用 `Clock.fixed` 钉死「今日」。
- 重复匹配委托 `CalendarRecurrenceMatcher`，与服务今日展开共用同一份逻辑。

### D6：微信日程早报——event_digest

- `CalendarReminderJobs`：`@Scheduled(cron="0 30 7 * * ?")`，逐家庭匹配今日日程，组装摘要，调 `WechatNotifyService.notifyEventDigest`。
- 接收人：日程有 assignee_profile_id → 查对应 user 的 openid；无 → 推家庭全员已绑定成员。
- 摘要渲染：wechat-service `event_digest` 事件，keyword1=「今日 N 件安排」、keyword2=前 5 条日程标题顿号拼接（超 40 字符截断加「…」），url=`/calendar`。
- 模板 ID 未配置 → 该事件 skipped 降级（与 Change 12 三事件一致）。

### D7：前端失败态与空态分离

- DashboardView 今日日程卡片、CalendarView 今日/全部日程区块、DashboardView 最近任务卡片：接口失败时渲染「没加载出来，请检查网络后重试」+ 大按钮重试，**不再谎报「今天没有日程」/「暂无任务」**。
- 订正假注释：原注释称失败可静默，实际 client.js 响应拦截器对非 0 code 与网络错误都会先弹 5 秒红色 toast，页面静默只会把「没拉到」渲染成「没有」。
- `MetricCard` 百分支路：`Number.isFinite(n) ? Math.round(n) : v`，`'--'` 占位不再渲染 `NaN%`。

### D8：配置与编排

| 位置 | 变量 | 默认 | 说明 |
|---|---|---|---|
| backend | 无新增（复用 ClockConfig） | — | — |
| wechat-service | `WECHAT_TPL_EVENT_DIGEST_ID` | 空 | 空 → event_digest 事件 skipped |
| compose | wechat-service environment 加该变量 | `:-` 空默认 | — |

### D9：不做的事

- 不做日程与任务双向同步（D1）
- 不做 yearly / interval 重复（freq 白名单仅限 daily/weekly/monthly）
- 不做提前量提醒（固定当日 07:30 早报）
- 不做月视图日历（列表 + 今日展开）
- 不引入 Testcontainers（H2 等效索引 + DDL 静态检查）

## Migration Plan

1. V11 迁移 + 实体 + 仓储（先跑通 H2 测试）
2. Task 扩展 + TaskSource.CALENDAR
3. CalendarEventService + 校验 + 今日展开
4. CalendarController API
5. CalendarSchedulerJobs + 幂等
6. CalendarReminderJobs + 微信早报
7. 前端 API 层 + CalendarView
8. 前端集成（路由/入口/卡片/角标）
9. 终审修复波（IMP-1 文档 / IMP-2 monthly 月末钳制 / IMP-3 失败态分离 + 同类谎报清偿 + MetricCard NaN）

## Open Questions

- 月度重复的「月末钳制」是否需要可配置（RFC 跳过 vs 钳制）——本期固定钳制，注释钉死决策。
- 日程早报摘要是否需要个性化（按执行人筛选）——本期家庭级摘要，后续按需优化。
