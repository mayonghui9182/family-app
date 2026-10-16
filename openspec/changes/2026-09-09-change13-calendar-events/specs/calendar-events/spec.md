# Delta Spec: calendar-events (Change 13 - 日程/日历能力)

> 新增独立日程实体能力：带具体时间点与重复规则的家庭事项，到期自动生成任务实例，支持微信日程早报。

## ADDED Requirements

### Requirement: 系统 SHALL 提供独立日程实体的 CRUD

系统 SHALL 提供 `calendar_events` 实体，字段含 family_id、title、description、event_date（一次性）、event_time（HH:mm，必填）、recurrence（JSONB，重复规则）、reminder_enabled、assignee_profile_id、category_id、is_active、软删。一次性日程 SHALL 满足 event_date 非空且 recurrence 为 NULL；重复日程 SHALL 满足 event_date 为 NULL 且 recurrence 非空（互斥由 CHECK 约束保证）。

#### Scenario: 创建一次性日程
- **WHEN** 用户提交 title=「喂药」、event_time=「08:00」、event_date=2026-09-20、recurrence=null
- **THEN** 日程创建成功，recurrence 落库为真 SQL NULL

#### Scenario: 日期与重复规则不能同时设置
- **WHEN** 用户同时设置 event_date 和 recurrence
- **THEN** 被 chk_ce_date_recurrence 约束拒绝

### Requirement: 重复规则 SHALL 仅支持 daily/weekly/monthly 且写路径校验

写路径（create/update）SHALL 校验 recurrence：freq 必须是 daily/weekly/monthly 之一；monthly.day 为 1..31 整数；weekly.weekdays 非空且元素为 1..7 整数（ISO 8601，1=周一）。校验失败 SHALL 抛 ResultException（47004 中文消息）。读路径 SHALL 保持前向兼容——未知 freq 静默不命中。

#### Scenario: 非法 freq 被拒绝
- **WHEN** create 时 recurrence={"freq":"yearly"}
- **THEN** 返回 47004 中文错误，日程不落库

#### Scenario: monthly day 越界被拒绝
- **WHEN** create 时 recurrence={"freq":"monthly","day":32}
- **THEN** 返回 47004 中文错误

### Requirement: monthly 重复 SHALL 采用月末钳制语义

monthly 重复的命中日 SHALL 为 `min(day, 当月天数)`。例如 day=31 在 2 月命中 28/29 号、在 4 月命中 30 号。day<=0 SHALL 恒不命中。刻意不采用 RFC 5545 的「跳过非存在日」语义。

#### Scenario: 月末钳制
- **WHEN** 日程 recurrence={"freq":"monthly","day":31}，今日为 2026-02-28
- **THEN** 该日程命中今日

#### Scenario: day 为 0 不命中
- **WHEN** 日程 recurrence={"freq":"monthly","day":0}
- **THEN** 任何日期都不命中

### Requirement: 系统 SHALL 每日 02:30 为当日命中日程生成 PENDING 任务

`CalendarSchedulerJobs` SHALL 在每日 02:30 遍历有启用日程的家庭，对匹配今日的日程生成 PENDING 任务（source=CALENDAR，due_date=今日，due_time=日程时间点，source_event_id=日程 ID）。逐家庭 SHALL try/catch 隔离，单家庭失败不阻断整批。

#### Scenario: 命中日程生成任务
- **WHEN** 02:30 调度运行，某家庭有 daily 日程「喂药 08:00」
- **THEN** 生成一条 PENDING 任务，title=「喂药」、due_time=「08:00」、source=CALENDAR、source_event_id=该日程 ID

#### Scenario: 单家庭失败不阻断
- **WHEN** 家庭 1 调度抛异常，家庭 2 有命中日程
- **THEN** 家庭 2 的任务正常生成，家庭 1 仅记错误日志

### Requirement: 日程→任务生成 SHALL 幂等

系统 SHALL 通过 `uq_tasks_event_date` partial unique index `UNIQUE (source_event_id, due_date) WHERE source_event_id IS NOT NULL AND deleted_at IS NULL` 保证同一日程同日只生成一条任务。调度器捕获 `DataAccessException` 后 SHALL 跳过（视为今日已生成）。

#### Scenario: 重复生成被唯一索引拒绝
- **WHEN** 同日对同一日程第二次生成任务
- **THEN** 第二条 insert 撞 uq_tasks_event_date 抛 DuplicateKeyException，被 catch 吞掉，任务表仅一条记录

#### Scenario: 不同日可重复生成
- **WHEN** 同一日程在不同日期生成任务
- **THEN** 两条任务均成功落库（due_date 不同）

### Requirement: 系统 SHALL 每日 07:30 推送日程早报到微信

`CalendarReminderJobs` SHALL 在每日 07:30 逐家庭匹配今日日程，组装摘要后通过微信模板消息 event_digest 推送。接收人：日程有 assignee_profile_id 时推给该执行人；无则推给家庭全员已绑定成员。模板 ID 未配置 SHALL 静默跳过。

#### Scenario: 有执行人推给执行人
- **WHEN** 日程有 assignee_profile_id 且对应用户已绑定微信
- **THEN** 仅该用户收到 event_digest 消息

#### Scenario: 模板未配置降级
- **WHEN** WECHAT_TPL_EVENT_DIGEST_ID 为空
- **THEN** 日程早报静默跳过，不报错

### Requirement: 日程早报摘要 SHALL 截断渲染

wechat-service 渲染 event_digest 时，keyword1 SHALL 为「今日 N 件安排」；keyword2 SHALL 为前 5 条日程标题顿号拼接，超过 5 条加「等」，渲染后超 40 字符 SHALL 截断为前 39 字符加「…」。≤40 字符 SHALL 原样透传。

#### Scenario: 超 5 条 + 超 40 字符截断
- **WHEN** 今日有 6 条日程，摘要拼接后 47 字符
- **THEN** keyword2 为前 5 条 join+「等」，渲染截断为 39 字符+「…」

#### Scenario: 少条目原样透传
- **WHEN** 今日有 2 条日程，摘要 26 字符
- **THEN** keyword2 原样透传不截断

### Requirement: 前端日程失败态 SHALL 与空态分离

DashboardView 今日日程卡片、CalendarView 今日/全部日程区块、DashboardView 最近任务卡片，接口失败时 SHALL 渲染「没加载出来，请检查网络后重试」+ 重试按钮，SHALL NOT 谎报「今天没有日程」/「暂无任务」/「还没有日程」。

#### Scenario: 今日日程接口失败
- **WHEN** calendarApi.today() 抛异常
- **THEN** 卡片显示失败态 + 重试按钮，不显示「今天没有日程安排」

#### Scenario: 全部日程接口失败
- **WHEN** calendarApi.list() 抛异常
- **THEN** 显示失败态 + 重试按钮，不显示「还没有日程」

### Requirement: 指标卡百分位 SHALL 不渲染 NaN

MetricCard 百分支路在值为 `'--'`（未就绪占位）时 SHALL 原样显示 `'--'`，SHALL NOT 渲染 `NaN%`。

#### Scenario: 未就绪占位不渲染 NaN
- **WHEN** 指标卡百分位值为 `'--'`
- **THEN** 显示 `--`，不出现 `NaN`
