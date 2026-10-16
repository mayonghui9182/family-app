# Proposal: Change 13 - 日程/日历能力

## Why

family-app 现有的「任务」模型只有截止日期（due_date），无法表达「每周二 09:00 喂药」「每月 15 号交房租」这类带具体时间点和重复规则的日程。老人家庭场景下，吃药、遛弯、接孩子等高频事项需要按时间点提醒，且希望一次设定、重复生效，不需要每天手动建任务。

本 change 引入独立的「日程」实体，支持具体时间点（HH:mm）、重复规则（每天/每周/每月），并在日程到期时自动生成 PENDING 任务实例，复用现有任务流（状态机、指派、分类、微信推送）。同时新增第 4 个微信模板消息事件——每日 07:30 日程早报。

## What Changes

- **数据模型**：
  - 新建 `calendar_events` 表（family_id / title / event_date / event_time / recurrence JSONB / reminder_enabled / assignee_profile_id / category_id / is_active / 软删）
  - `tasks` 表扩展 `due_time`（VARCHAR(5)）、`source_event_id`（BIGINT）两列
  - 幂等兜底：`uq_tasks_event_date` partial unique index `ON tasks (source_event_id, due_date) WHERE source_event_id IS NOT NULL AND deleted_at IS NULL`（生产 PG，afterMigrate 回调）
- **后端**（`com.familyapp.calendar` 包）：
  - `CalendarEvent` 实体 + `CalendarEventRepository`
  - `CalendarEventService`：CRUD + 重复规则校验（写路径白名单）+ 今日展开
  - `CalendarRecurrenceMatcher`：daily / weekly(weekdays) / monthly(day，月末钳制) 命中判断
  - `CalendarController`：`/api/calendar/events`（GET/POST）、`/api/calendar/events/{id}`（GET/PUT/DELETE）、`/api/calendar/today`（GET）
  - `CalendarSchedulerJobs`：每日 02:30 扫描启用日程，匹配今日重复规则生成 PENDING 任务（source=CALENDAR，带 due_time + source_event_id），逐家庭隔离，幂等靠唯一索引兜底
  - `CalendarReminderJobs`：每日 07:30 推送今日日程早报（微信模板 event_digest）
- **微信**：
  - wechat-service 新增 `event_digest` 事件渲染（keyword1=「今日 N 件安排」、keyword2=日程摘要，url=`/calendar`）
  - backend `WechatNotifyService.notifyEventDigest` 接线
  - 第 4 个模板 ID：`WECHAT_TPL_EVENT_DIGEST_ID`
- **前端**：
  - `api/calendar.js`：6 个端点封装
  - `CalendarView.vue`：日程管理页面（老人易用：大字号、大按钮、中文标签、emoji）
  - 集成：`/calendar` 路由、更多菜单入口、Dashboard 今日日程卡片、任务卡 📅 角标
  - 失败态与空态分离（今日日程 / 全部日程 / 最近任务三处）
  - `MetricCard` 百分位 `'--'` 占位不渲染 NaN

## 非目标 (Non-goals)

- 不做日程与任务的双向同步（任务状态变更不回写日程；日程修改不追溯已生成任务）
- 不做按年重复、按间隔重复（freq 仅限 daily/weekly/monthly）
- 不做日程提醒的提前量配置（reminder_enabled 仅布尔，固定当日 07:30 早报）
- 不做日历月视图（本期只有列表 + 今日展开）
- 不做 Testcontainers 真实 PG 集成测试（H2 不支持 partial index，幂等索引用等效唯一索引 + DDL 静态检查兜底）
- 真机微信联调 DEFERRED 给用户（测试号后台建第 4 模板、填 ID）

## Capabilities

### Modified Capabilities

- `daily-task-mgmt`：tasks 表新增 due_time / source_event_id；TaskSource 新增 CALENDAR；02:30 调度由日程生成任务
- `wechat-integration`：新增第 4 个模板消息事件 event_digest（每日日程早报）

### Added Capabilities

- `calendar-events`：独立日程实体的 CRUD、重复规则匹配、今日展开、自动生成任务、微信早报

## Impact

- backend：`com/familyapp/calendar/` 新增 6 类 + DTO；`task/Task.java` 加 2 字段；`task/TaskSource.java` 加 CALENDAR；`task/dto/TaskResponse.java` 加 2 字段；`wechat/WechatNotifyService.java` 加 notifyEventDigest；`afterMigrate__agent_reader.sql` 追加 partial unique index；V11 迁移脚本
- wechat-service：`app/wechat/template.py` 加 event_digest 渲染；`app/settings.py` 加 WECHAT_TPL_EVENT_DIGEST_ID；测试 +1
- frontend：`api/calendar.js`、`views/CalendarView.vue`、router/menu/dashboard/taskcard 集成、MetricCard 修复
- docker-compose / .env.example：补 WECHAT_TPL_EVENT_DIGEST_ID
