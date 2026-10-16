# Check List：日程/日历能力（Change 13）

> 状态：代码侧全部 TDD 完成并贴证；真机微信联调（第 4 模板）为 DEFERRED（用户侧）。

## 一、数据模型与迁移（Task 1-2）

| # | 验收项 | 状态 | 证据 |
|---|--------|------|------|
| 1 | calendar_events 表创建，含 CHECK 约束（时间格式 / 日期与重复互斥） | ✅ | V11MigrationTest 用例 1/3/4 |
| 2 | tasks 表新增 due_time / source_event_id 列 | ✅ | V11MigrationTest 用例 2 |
| 3 | Repository 落库一次性日程后 recurrence 为真 SQL NULL（非 'null' 字面量） | ✅ | V11MigrationTest 用例 5/6 |
| 4 | uq_tasks_event_date partial unique index DDL 正确（含 WHERE 条件） | ✅ | CalendarTaskIdempotencyTest 用例 2（静态检查 afterMigrate） |
| 5 | Task 实体有 dueTime / sourceEventId 字段 | ✅ | TaskCalendarFieldsTest |
| 6 | TaskSource 含 CALENDAR | ✅ | TaskCalendarFieldsTest |
| 7 | TaskResponse 映射 dueTime / sourceEventId | ✅ | TaskCalendarFieldsTest |

## 二、CalendarEventService + 重复匹配（Task 3）

| # | 验收项 | 状态 | 证据 |
|---|--------|------|------|
| 8 | create 一次性日程成功（recurrence=NULL） | ✅ | CalendarEventServiceTest |
| 9 | create 重复日程成功（recurrence JSONB） | ✅ | CalendarEventServiceTest |
| 10 | 标题为空 / 时间格式错 → ResultException 中文消息 | ✅ | CalendarEventServiceTest |
| 11 | eventDate 与 recurrence 同时设置 → 拒绝 | ✅ | CalendarEventServiceTest + CHECK 约束 |
| 12 | 写路径 recurrence 白名单：freq 非 daily/weekly/monthly 拒绝；monthly.day 越界拒绝；weekly.weekdays 越界拒绝 | ✅ | CalendarEventServiceTest +8（白名单 6 + 边界反向 + PATCH 不绕过） |
| 13 | update / delete（软删）/ list / get 正常 | ✅ | CalendarEventServiceTest |
| 14 | todayEvents 展开重复规则命中今日 | ✅ | CalendarEventServiceTest |
| 15 | CHILD 角色写操作 → 40302 | ✅ | CalendarEventServiceTest |
| 16 | daily 每天命中 | ✅ | CalendarRecurrenceMatcherTest + CalendarSchedulerJobsTest |
| 17 | weekly 按 weekdays 命中（ISO 1..7） | ✅ | CalendarRecurrenceMatcherTest |
| 18 | monthly 命中日 = min(day, 当月天数)（月末钳制）；day<=0 不命中 | ✅ | CalendarRecurrenceMatcherTest 11 例 |

## 三、CalendarController API（Task 4）

| # | 验收项 | 状态 | 证据 |
|---|--------|------|------|
| 19 | POST /api/calendar/events → 201 | ✅ | CalendarControllerTest |
| 20 | GET /api/calendar/events 列表 | ✅ | CalendarControllerTest |
| 21 | GET/PUT/DELETE /api/calendar/events/{id} | ✅ | CalendarControllerTest |
| 22 | GET /api/calendar/today 今日展开 | ✅ | CalendarControllerTest |
| 23 | CHILD 角色 POST → 403 | ✅ | CalendarControllerTest |
| 24 | 标题为空 → 400 + 中文消息 | ✅ | CalendarControllerTest |

## 四、CalendarSchedulerJobs 幂等（Task 5）

| # | 验收项 | 状态 | 证据 |
|---|--------|------|------|
| 25 | 02:30 cron 存在，逐家庭遍历 | ✅ | CalendarSchedulerJobsTest |
| 26 | 命中日程生成任务（source=CALENDAR / dueDate=今日 / dueTime / sourceEventId） | ✅ | CalendarSchedulerJobsTest 用例 1 |
| 27 | 非命中日程不生成 | ✅ | CalendarSchedulerJobsTest 用例 2/4/7 |
| 28 | 停用日程不生成（active-only 仓储口径） | ✅ | CalendarSchedulerJobsTest 用例 8 |
| 29 | DB 层唯一索引挡重复 insert（同 source_event_id + due_date 撞 DuplicateKeyException） | ✅ | CalendarTaskIdempotencyTest 用例 1 |
| 30 | 同 source_event_id 不同 due_date 不冲突 | ✅ | CalendarTaskIdempotencyTest 用例 1b |
| 31 | catch 吞 DataAccessException 不外溢（幂等跳过） | ✅ | CalendarSchedulerJobsTest 用例 9 |
| 32 | 单家庭异常不阻断其他家庭 | ✅ | CalendarSchedulerJobsTest 用例 10 |

## 五、CalendarReminderJobs + 微信早报（Task 6）

| # | 验收项 | 状态 | 证据 |
|---|--------|------|------|
| 33 | 07:30 cron 存在，逐家庭匹配今日日程 | ✅ | CalendarReminderJobsTest |
| 34 | 有 assigneeProfileId → 推给执行人；无 → 推全员已绑定 | ✅ | CalendarReminderJobsTest |
| 35 | 无今日日程 → 不推送 | ✅ | CalendarReminderJobsTest |
| 36 | 模板 ID 留空 → 静默跳过（skipped 降级） | ✅ | CalendarReminderJobsTest + test_template.py |
| 37 | event_digest 渲染：keyword1=今日 N 件 / keyword2=摘要（超 5 条拼接+超 40 字符截断）/ url=/calendar | ✅ | test_template.py 用例 d + few_entries |
| 38 | 推送异常全链路吞掉，不影响业务 | ✅ | CalendarReminderJobsTest |

## 六、前端（Task 7-8）

| # | 验收项 | 状态 | 证据 |
|---|--------|------|------|
| 39 | api/calendar.js 6 端点封装 | ✅ | calendar.test.js |
| 40 | /calendar 路由 + 更多菜单入口 + 侧边栏菜单 | ✅ | CalendarRoute.test.js |
| 41 | DashboardView 今日日程卡片 | ✅ | CalendarRoute.test.js |
| 42 | TaskCardMobile 📅 角标（source=CALENDAR） | ✅ | 代码审查 |
| 43 | 今日日程失败态（重试按钮，不谎报空态） | ✅ | CalendarRoute.test.js 用例 6/6b |
| 44 | 最近任务失败态（不谎报「暂无任务」） | ✅ | CalendarRoute.test.js 用例 6c/6d |
| 45 | CalendarView 今日/全部日程失败态（allSettled 隔离） | ✅ | CalendarView.test.js 用例 2b/2c/2d/2e |
| 46 | MetricCard 百分位 `'--'` 不渲染 NaN | ✅ | MetricCard.test.js 用例 8 + CalendarRoute.test.js 用例 8 |

## 七、自动化门禁

| 命令 | 结果 |
|------|------|
| `backend: ./gradlew test --continue --rerun-tasks` | ✅ **370+ tests / 0 failures**（含 CalendarTaskIdempotencyTest 3 新用例） |
| `wechat-service: pytest -q` | ✅ **30 passed，coverage 89.95%** |
| `frontend: npx vitest run` | ✅ **23 files / 113 tests passed** |
| `frontend: npm run build` | ✅ built 成功 |

## 八、DEFERRED（用户侧）

- [ ] 微信测试号后台建第 4 个模板「每日家庭日程早报」（正文范例见 deployment-guide 7.4）
- [ ] `.env` 填 WECHAT_TPL_EVENT_DIGEST_ID（当前为空，日程早报静默跳过）
- [ ] 真机验证：等 07:30 或手动触发 → 收 event_digest 日程早报（点跳 /calendar）
- [x] 存量库 flywayMigrate 复查 uq_tasks_event_date 索引存在（pg_indexes）。**已于 2026-09-26 复查通过**：`docker exec familyapp-postgres psql -U familyapp_user -d familyapp` 直查 `pg_indexes`，`uq_tasks_event_date` 已存在（幂等重建生效）。
