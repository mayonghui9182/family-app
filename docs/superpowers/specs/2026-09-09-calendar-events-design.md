# 设计：日程/日历能力（Change 13）

> 日期：2026-09-09 · 状态：待用户审阅 → 转入实施计划

## 1. 背景与目标

当前 family-app 只有「任务」模型（`tasks.due_date` 仅日期粒度），缺少真正的「日程/事件」能力。大人的日常工作常有周期性事件，如「周二 10:00 周会，每周重复」，无法被现有模型正确表达（无具体时间点、无重复规则、无提醒）。

**目标**：增加独立的日程实体，支持具体时间点、重复规则（每天/每周/每月）、微信早报提醒；日程到期时自动生成任务实例，复用现有任务流与微信推送通道。

## 2. 总体路线

**日程定义 → 到期自动生成任务实例**，复用现有任务流与微信推送通道。

- 日程是**独立一等实体**（新表 `calendar_events`），不污染 5 值任务状态机
- 既有 `rule_templates(type=routine)`（AI 自动化场景）保持不动
- 日程生成的任务带 `due_time` 时间点，与手动任务区分

## 3. 边界决策表（全部按建议定稿）

| # | 决策项 | 结论 |
|---|--------|------|
| 1 | 一次性 vs 重复互斥 | `event_date` 与 `recurrence` 互斥，有 recurrence 则 event_date 为 NULL |
| 2 | `event_time` 必填 | 必填，不支持全天事件 |
| 3 | 执行人可空 | `assignee_profile_id` 可空（家庭全员日程） |
| 4 | 分类可空 | `category_id` 可空 |
| 5 | 删除方式 | 软删（`deleted_at`），与 Task/Category 一致 |
| 6 | 每周星期选择 | 多选（如每周二、四） |
| 7 | 每月重复 | 仅「每月固定几号」，不做「第几个星期几」 |
| 8 | 重复结束条件 | 无限重复 + 手动停用，不设结束日期 |
| 9 | 单次跳过例外 | 不做，需要时用户删任务 |
| 10 | 生成时机 | 每日 02:30 只生成今日任务，不预生成未来 |
| 11 | 生成任务状态 | PENDING |
| 12 | 任务标题 | 直接用日程标题，时间靠 `due_time` 结构化展示 |
| 13 | 任务优先级 | 日程不带优先级，生成任务默认 MEDIUM |
| 14 | 幂等兜底 | `(source_event_id, due_date)` 部分唯一索引防重复生成 |
| 15 | 停用联动 | 停用日程不联动已生成的任务，只阻止未来生成 |
| 16 | 提醒粒度 | 仅每日早报（07:30 推今日全部日程），不做分钟级精确提醒 |
| 17 | 提醒对象 | 推送给日程指定的执行人；未指定执行人则推送给全员 |
| 18 | 未绑定微信成员 | 仅 App 内展示，不做其他通道 |
| 19 | 权限 | OWNER/ADULT 可建可改，CHILD 只能查看 |
| 20 | 跨家庭隔离 | 所有查询必须带 family_id（硬约束） |
| 21 | 前端入口 | 「更多功能」菜单加「📅 日程」，不动底部 Tab 栏 |
| 22 | 首页卡片 | Dashboard 加「今日日程」卡片 |
| 23 | 任务角标 | 日程生成的任务加「📅」角标区分 |
| 24 | 重复日程编辑 | 只支持改日程定义（影响所有未来实例），不支持改单次 |
| 25 | 与 routine 分工 | 日程=具体时间点事件；routine=AI 拆解日常任务，分开放「日程」「自动化」入口 |
| 26 | 与 task_schedules 关系 | 不合并，一次性日程走日历调度，task_schedules 服务 AI 拆解链路 |

## 4. 数据模型（V11 迁移）

### 4.1 新表 `calendar_events`

```sql
CREATE TABLE calendar_events (
    id                    BIGSERIAL PRIMARY KEY,
    family_id             BIGINT       NOT NULL,
    title                 VARCHAR(200) NOT NULL,
    description           TEXT,
    event_date            DATE,                  -- 一次性事件日期（重复事件为 NULL）
    event_time            VARCHAR(5)  NOT NULL,  -- HH:mm，必填
    recurrence            JSONB,                 -- 重复规则，NULL=一次性
    reminder_enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
    assignee_profile_id   BIGINT,                -- 可空，FK person_profiles
    category_id           BIGINT,                -- 可空，FK categories
    is_active             BOOLEAN     NOT NULL DEFAULT TRUE,
    created_by            BIGINT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at            TIMESTAMPTZ
);
```

索引：
- `idx_calendar_events_family` ON `(family_id)`
- `idx_calendar_events_active` ON `(family_id, is_active)` WHERE `deleted_at IS NULL`

约束：
- `chk_calendar_recurrence_xor_date`：`(event_date IS NULL) <> (recurrence IS NOT NULL)` —— 一次性与重复互斥
- `chk_calendar_time_format`：`event_time ~ '^\d{2}:\d{2}$'`

`recurrence` JSONB 结构：
```jsonc
// 每天
{"freq": "daily"}
// 每周（weekdays 为 ISO 周一=1..周日=7，数组）
{"freq": "weekly", "weekdays": [2, 4]}
// 每月固定几号
{"freq": "monthly", "day": 15}
```

### 4.2 `tasks` 表扩展

```sql
ALTER TABLE tasks ADD COLUMN due_time       VARCHAR(5);  -- HH:mm，可空
ALTER TABLE tasks ADD COLUMN source_event_id BIGINT;     -- 来源日程 ID，可空

-- 幂等唯一索引：同一日程同一天只生成一条任务
CREATE UNIQUE INDEX uq_tasks_event_date
    ON tasks (source_event_id, due_date)
    WHERE source_event_id IS NOT NULL AND deleted_at IS NULL;
```

### 4.3 枚举扩展

`TaskSource` 新增 `CALENDAR`。

## 5. 后端设计

### 5.1 实体与仓储

- `CalendarEvent` 实体（`com.familyapp.calendar` 包）：字段映射见 4.1，`@Where(clause = "deleted_at IS NULL")`
- `CalendarEventRepository`：`findByFamilyId`、`findByFamilyIdAndIsActiveTrue`

### 5.2 API 端点（`/api/calendar`）

| 端点 | 方法 | 说明 |
|------|------|------|
| `/events` | GET | E-1 列表（本家庭全部日程，含已停用？默认只返 active，`?includeInactive=true` 可含） |
| `/events` | POST | E-2 创建（OWNER/ADULT） |
| `/events/{id}` | GET | E-3 详情 |
| `/events/{id}` | PUT | E-4 更新（OWNER/ADULT） |
| `/events/{id}` | DELETE | E-5 软删（OWNER/ADULT） |
| `/today` | GET | E-6 今日日程实例展开（供首页卡片，按时间排序） |

所有端点需 `X-Access-Token` + `X-Family-Id`；CHILD 只能 GET，写操作返回 40302。

### 5.3 调度器 `CalendarSchedulerJobs`

```java
@Scheduled(cron = "0 30 2 * * ?")  // 每日 02:30
public void generateTodayTasks() {
    // 1. 查所有 family_id 有 active 日程的家庭
    // 2. 逐家庭：匹配今日重复规则 → 生成 PENDING 任务（带 due_date + due_time + source_event_id）
    // 3. 唯一索引兜底幂等（UniqueViolation 即跳过）
    // 4. 逐家庭 try/catch 隔离
}
```

匹配逻辑复用 `RoutineMatcherService` 的思路（weekly/monthly 求值），daily 直接命中。生成任务走 `DirectTaskCreator` 的新增方法 `createFromCalendarEvent(event, dueDate)`。

### 5.4 提醒 `CalendarReminderJobs`

```java
@Scheduled(cron = "0 30 7 * * ?")  // 每日 07:30
public void sendDailyDigest() {
    // 1. 查所有家庭今日日程（reminder_enabled=true）
    // 2. 逐家庭：组装今日日程清单 → 推微信模板消息
    //    - 有 assignee_profile_id → 推该用户
    //    - 无 assignee_profile_id → 推家庭全员已绑定微信成员
    // 3. 模板 ID 留空则静默跳过（沿用 Change 12 降级约定）
    // 4. 全链路异常吞在通知层
}
```

新增微信模板 `WECHAT_TPL_EVENT_DIGEST_ID`，内容：
```
{{first.DATA}}
今日日程：{{keyword1.DATA}} 条
时间：{{keyword2.DATA}}
{{remark.DATA}}
```

> **实现偏离订正（终审 IMP-1）**：上段正文是设计期草案，**实际渲染以
> `wechat-service/app/wechat/template.py` 的 `event_digest` 分支为准**——`keyword1` 的值自带
> 「今日 N 件安排」整句、`keyword2` 是日程摘要（超 40 字截断）。照上段标签建模板会串成
> 「今日日程：今日 3 件安排 条」。测试号建模板请用部署文档 7.4 第 3 步表格里的槽位写法。

### 5.5 ResultCode 新增

```
CALENDAR_TITLE_EMPTY(47001, "日程标题不能为空")
CALENDAR_TIME_INVALID(47002, "日程时间格式不正确")
CALENDAR_DATE_RECURRENCE_CONFLICT(47003, "一次性日期与重复规则不能同时设置")
CALENDAR_RECURRENCE_INVALID(47004, "重复规则格式不正确")
CALENDAR_NOT_FOUND(47005, "日程不存在")
CALENDAR_CHILD_FORBIDDEN(40302, "孩子成员不能创建或修改日程")
```

## 6. 前端设计

### 6.1 新页面 `CalendarView.vue`

- 列表：大字号日程卡片，显示标题、时间、重复规则中文描述（如「每周二、四 10:00」）、执行人、提醒开关
- 新建/编辑表单：
  - 标题（大输入框）
  - 时间（el-time-picker，HH:mm）
  - 类型单选：不重复 / 每天 / 每周 / 每月
    - 每周 → 星期多选（一~日 大按钮）
    - 每月 → 几号数字输入
  - 日期（不重复时显示 el-date-picker）
  - 执行人（可空，下拉家庭成员档案）
  - 分类（可空）
  - 提醒开关（默认开）
- 删除需二次确认（el-message-box）

### 6.2 首页卡片 `DashboardView`

新增「今日日程」卡片，调 E-6，按时间排序展示今日日程（标题 + 时间 + 执行人）。

### 6.3 任务卡片角标

`TaskCardMobile` / 桌面任务卡片：`source === 'CALENDAR'` 时显示「📅」角标。

### 6.4 路由与入口

- `router/index.js` 新增 `calendar` 路由（懒加载 `CalendarView.vue`）
- `MoreMenuView.vue` 加「📅 日程」入口

## 7. 与现有机制的分工

| 能力 | 日程（Change 13） | routine 模板 | task_schedules |
|------|-------------------|-------------|----------------|
| 场景 | 具体时间点的事件（周会、复诊） | AI 拆解的日常任务（浇花、买莱） | AI 拆解链路的一次性生成 |
| 时间粒度 | 有 HH:mm | 仅日期 | 仅日期 |
| 生成任务 | ✅ 带 due_time + source_event_id | ✅ source=ROUTINE | ✅ source=SCHEDULE |
| 入口 | 「日程」页 | 「自动化/模板」页 | 内部，无 UI |

## 8. 验证策略

### 8.1 后端
- V11 迁移测试：表结构、约束、唯一索引
- CRUD 测试：五端点权限（CHILD 写操作 403）、互斥约束、时间格式校验
- 调度测试：
  - weekly 周二匹配今日生成任务
  - 唯一索引幂等（重复跑不重复生成）
  - 停用日程不生成
- 提醒测试：模板 ID 留空静默跳过；有执行人推该用户；无执行人推全员
- `./gradlew test` 全绿（基线 263 + 新增）

### 8.2 前端
- vitest 新增：日程表单校验、重复规则切换、CHILD 只读态
- `npm run build` 无报错

### 8.3 真机联调（DEFERRED）
- 在微信测试号建「今日日程早报」模板，验证 07:30 推送
- 验证周会任务带 10:00 时间展示

## 9. 明确不做（YAGNI）

- 月视图/拖拽日历（列表式更适合老人）
- iCal/RRULE 标准（简化 JSON 结构）
- 分钟级精确提醒、跨家庭日程
- 「每月第几个星期几」规则
- 重复结束日期
- 单次跳过例外
- 重复日程的单次编辑
