# Tasks: Change 13 - 日程/日历能力

> 状态：全部 Task 已实施完成并入库（10+ commit，均本地未 push）。以下为回填记录，
> 反映实际实施顺序与最终状态。

- [x] **Task 1：V11 迁移 + CalendarEvent 实体 + Repository**
  - V11__Add_Calendar_Events.sql：calendar_events 表（含 CHECK 约束 chk_ce_time_format / chk_ce_date_recurrence）+ tasks 表加 due_time / source_event_id
  - afterMigrate__agent_reader.sql 追加 uq_tasks_event_date partial unique index
  - CalendarEvent 实体（JSONB recurrence 用 @JdbcTypeCode(SqlTypes.JSON)）
  - CalendarEventRepository（findByFamilyIdAndDeletedAtIsNull / findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull / findByIdAndFamilyIdAndDeletedAtIsNull / findDistinctFamilyIdsWithActiveEvents）
  - V11MigrationTest：6 用例（表/列创建 + 互斥约束 + 时间格式 + Repository 落库 recurrence 真 SQL NULL）

- [x] **Task 2：Task 实体扩展 + TaskSource.CALENDAR + TaskResponse**
  - Task.java 加 dueTime / sourceEventId 字段及 getter/setter
  - TaskSource.java 加 CALENDAR 枚举值
  - TaskResponse.java record 加 dueTime / sourceEventId
  - TaskCalendarFieldsTest：3 用例（字段存在 / 枚举存在 / Response 映射）

- [x] **Task 3：CalendarEventService（CRUD + 校验 + 今日展开）**
  - DTO：CreateCalendarEventRequest / UpdateCalendarEventRequest / CalendarEventResponse / TodayEventItem
  - CalendarEventService：create / update / delete（软删）/ list / get / todayEvents
  - 写路径 recurrence 校验白名单（freq / monthly.day / weekly.weekdays）
  - CalendarRecurrenceMatcher：daily / weekly / monthly（月末钳制）命中判断
  - 权限：CHILD 角色写操作抛 40302
  - CalendarEventServiceTest：9 用例

- [x] **Task 4：CalendarController（API 端点 E-1~E-6）**
  - GET/POST /api/calendar/events
  - GET/PUT/DELETE /api/calendar/events/{id}
  - GET /api/calendar/today
  - CalendarControllerTest（MockMvc）：8 用例

- [x] **Task 5：CalendarSchedulerJobs（每日 02:30 生成任务）**
  - 逐家庭 try/catch 隔离
  - createTaskFromEvent：source=CALENDAR / dueDate=今日 / dueTime / sourceEventId
  - catch DataAccessException 幂等跳过
  - Clock 注入，CalendarRecurrenceMatcher 真实实例
  - CalendarSchedulerJobsTest：10 用例（含用例 9 mock 幂等吞异常 + 用例 10 家庭隔离）

- [x] **Task 6：CalendarReminderJobs + 微信日程早报**
  - CalendarReminderJobs：07:30 逐家庭匹配今日日程 → 组装摘要 → notifyEventDigest
  - WechatNotifyService.notifyEventDigest：接收人（执行人 or 全员）+ 吞异常
  - wechat-service：event_digest 事件渲染（keyword1 今日 N 件 / keyword2 摘要截断 / url=/calendar）
  - settings.py 加 WECHAT_TPL_EVENT_DIGEST_ID
  - CalendarReminderJobsTest + test_template.py 新用例

- [x] **Task 7：前端 API 层 + CalendarView 页面**
  - api/calendar.js：6 端点封装
  - CalendarView.vue：老人易用（大字号、大按钮、中文标签、emoji），重复规则大按钮单选
  - calendar.test.js：API 层 mock 测试

- [x] **Task 8：前端集成（路由 + 入口 + 卡片 + 角标）**
  - router/index.js 加 /calendar 路由
  - MoreMenuView / AppSider 加日程入口
  - DashboardView 加今日日程卡片
  - TaskCardMobile 加 📅 角标
  - CalendarRoute.test.js：路由/入口断言

- [x] **终审修复波**
  - IMP-1：deployment-guide.md + scripts/dev.sh 补第 4 模板文档（12 处替换）
  - IMP-2：monthly 月末钳制 + 写路径 recurrence 白名单校验（CalendarRecurrenceMatcherTest 11 例 + CalendarEventServiceTest +8）
  - IMP-3：DashboardView 今日日程失败态与空态分离（eventsError + 重试按钮）
  - 同类谎报清偿：DashboardView 最近任务 tasksError + CalendarView 今日 todayError / 全部 listError（共 3 处）
  - MetricCard NaN：百分支路 `Number.isFinite(n) ? Math.round(n) : v`，`'--'` 占位不渲染 NaN

- [x] **补测试欠债（2026-09-11）**
  - CalendarTaskIdempotencyTest：DB 层唯一索引挡重复（H2 等效非 partial 索引）+ 不同 due_date 不冲突 + afterMigrate DDL 静态检查（3 用例）
