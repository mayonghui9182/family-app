# 日程/日历能力（Change 13）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 family-app 增加独立日程实体，支持具体时间点、重复规则（每天/每周/每月）、微信早报提醒；日程到期时自动生成任务实例。

**Architecture:** 新建 `calendar_events` 表 + `com.familyapp.calendar` 包（实体/仓储/服务/控制器/调度器）。日程到期时经 `DirectTaskCreator` 生成带 `due_time` 的 PENDING 任务，复用现有任务流。微信早报复用 `WechatNotifyClient` 通道。前端新增 `CalendarView.vue` 页面，入口在「更多功能」菜单。

**Tech Stack:** Java 17 / Spring Boot 3 / JPA + Hibernate 6 / PostgreSQL (H2 test) / Flyway / Vue 3 / Element Plus / Vitest

## Global Constraints

- 所有代码注释使用中文
- ResultCode/异常消息必须中文，适配老人用户
- 所有业务查询必须带 family_id 隔离
- 5 值任务状态机不变（DRAFT/PENDING/IN_PROGRESS/COMPLETED/CANCELLED）
- 前端老人易用：大字号、高对比、中文标签、emoji 图标
- H2 兼容：partial index 不在 V11 主文件中创建，放 afterMigrate 回调（生产 PG）
- JSONB 映射用 `@JdbcTypeCode(SqlTypes.JSON)` + Jackson JsonNode
- 调度器逐家庭 try/catch 隔离，单家庭失败不阻断整批
- 微信推送异常全链路吞掉，不影响业务主流程

---

### Task 1: V11 迁移 + CalendarEvent 实体 + Repository

**Files:**
- Create: `backend/src/main/resources/db/migration/V11__Add_Calendar_Events.sql`
- Modify: `backend/src/main/resources/db/migration/afterMigrate__agent_reader.sql`（追加 partial unique index）
- Create: `backend/src/main/java/com/familyapp/calendar/CalendarEvent.java`
- Create: `backend/src/main/java/com/familyapp/calendar/CalendarEventRepository.java`
- Test: `backend/src/test/java/com/familyapp/V11MigrationTest.java`

**Interfaces:**
- Produces: `CalendarEvent` 实体（字段见下）、`CalendarEventRepository`（`findByFamilyIdAndDeletedAtIsNull`、`findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull`、`findDistinctFamilyIdsWithActiveEvents`）

- [ ] **Step 1: 写 V11 迁移 SQL**

```sql
-- ========================================================================
-- V11: Add Calendar Events（Change 13 日程/日历）
-- calendar_events：独立日程实体（一次性/重复，带时间点和提醒）
-- tasks 扩展：due_time + source_event_id
-- ========================================================================
-- H2 兼容说明（沿用 V6/V10 策略）：
--   1. JSONB 在 H2 MODE=PostgreSQL 下原生支持
--   2. partial unique index H2 不支持，在 afterMigrate 回调中执行（生产 PG）
--   3. CHECK 约束 H2 支持
-- ========================================================================

-- ============================================================
-- Step 1: calendar_events 表
-- ============================================================
CREATE TABLE IF NOT EXISTS calendar_events (
    id                    BIGSERIAL PRIMARY KEY,
    family_id             BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    title                 VARCHAR(200) NOT NULL,
    description           TEXT,
    event_date            DATE,
    event_time            VARCHAR(5) NOT NULL,
    recurrence            JSONB,
    reminder_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    assignee_profile_id   BIGINT REFERENCES person_profiles(id) ON DELETE SET NULL,
    category_id           BIGINT REFERENCES categories(id) ON DELETE SET NULL,
    is_active             BOOLEAN NOT NULL DEFAULT TRUE,
    created_by            BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at            TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_ce_time_format CHECK (event_time ~ '^\d{2}:\d{2}$'),
    CONSTRAINT chk_ce_date_recurrence CHECK (
        (event_date IS NOT NULL AND recurrence IS NULL)
        OR (event_date IS NULL AND recurrence IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_calendar_events_family ON calendar_events (family_id);
CREATE INDEX IF NOT EXISTS idx_calendar_events_active ON calendar_events (family_id, is_active);

-- ============================================================
-- Step 2: tasks 表扩展
-- ============================================================
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS due_time VARCHAR(5);
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS source_event_id BIGINT;

-- 常规索引（H2 兼容）；partial unique 在 afterMigrate 中补建（生产 PG）
CREATE INDEX IF NOT EXISTS idx_tasks_source_event ON tasks (source_event_id, due_date);
```

- [ ] **Step 2: 追加 afterMigrate partial unique index**

在 `afterMigrate__agent_reader.sql` 末尾追加：

```sql
-- Change 13: 日程任务幂等唯一索引（生产 PG 专用，H2 不支持 partial index）
CREATE UNIQUE INDEX IF NOT EXISTS uq_tasks_event_date
    ON tasks (source_event_id, due_date)
    WHERE source_event_id IS NOT NULL AND deleted_at IS NULL;
```

- [ ] **Step 3: 写 CalendarEvent 实体**

```java
package com.familyapp.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 日程实体（Change 13）：独立于任务的事件模型。
 * 支持一次性（event_date）或重复（recurrence JSONB），带具体时间点（event_time）。
 */
@Entity
@Table(name = "calendar_events")
@Where(clause = "deleted_at IS NULL")
public class CalendarEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** 一次性事件日期（重复事件为 NULL） */
    @Column(name = "event_date")
    private LocalDate eventDate;

    /** 具体时间点 HH:mm（必填） */
    @Column(name = "event_time", nullable = false, length = 5)
    private String eventTime;

    /**
     * 重复规则 JSONB：
     * {"freq":"daily"} / {"freq":"weekly","weekdays":[2,4]} / {"freq":"monthly","day":15}
     * NULL 表示一次性事件。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recurrence")
    private JsonNode recurrence;

    @Column(name = "reminder_enabled", nullable = false)
    private Boolean reminderEnabled = Boolean.TRUE;

    @Column(name = "assignee_profile_id")
    private Long assigneeProfileId;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = Boolean.TRUE;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.isActive == null) this.isActive = Boolean.TRUE;
        if (this.reminderEnabled == null) this.reminderEnabled = Boolean.TRUE;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // getter/setter 省略（实际代码中必须完整生成）
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long familyId) { this.familyId = familyId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDate getEventDate() { return eventDate; }
    public void setEventDate(LocalDate eventDate) { this.eventDate = eventDate; }
    public String getEventTime() { return eventTime; }
    public void setEventTime(String eventTime) { this.eventTime = eventTime; }
    public JsonNode getRecurrence() { return recurrence; }
    public void setRecurrence(JsonNode recurrence) { this.recurrence = recurrence; }
    public Boolean getReminderEnabled() { return reminderEnabled; }
    public void setReminderEnabled(Boolean reminderEnabled) { this.reminderEnabled = reminderEnabled; }
    public Long getAssigneeProfileId() { return assigneeProfileId; }
    public void setAssigneeProfileId(Long assigneeProfileId) { this.assigneeProfileId = assigneeProfileId; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}
```

- [ ] **Step 4: 写 CalendarEventRepository**

```java
package com.familyapp.calendar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * 日程仓储（Change 13）
 */
public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {

    /** 按家庭查所有未删除日程 */
    List<CalendarEvent> findByFamilyIdAndDeletedAtIsNull(Long familyId);

    /** 按家庭查启用中的日程（调度器用） */
    List<CalendarEvent> findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(Long familyId);

    /** 查单条（family_id 作用域） */
    Optional<CalendarEvent> findByIdAndFamilyIdAndDeletedAtIsNull(Long id, Long familyId);

    /** 查有启用日程的家庭 ID 列表（调度器逐家庭遍历用） */
    @Query("SELECT DISTINCT e.familyId FROM CalendarEvent e WHERE e.isActive = true AND e.deletedAt IS NULL")
    List<Long> findDistinctFamilyIdsWithActiveEvents();
}
```

- [ ] **Step 5: 写 V11MigrationTest**

```java
package com.familyapp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V11 迁移脚本验证测试（H2 兼容模式，Change 13）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
@Sql(scripts = {
    "classpath:db/v6-test-setup.sql",
    "classpath:db/migration/V11__Add_Calendar_Events.sql"
})
class V11MigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 用例1_calendar_events表创建成功() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = 'calendar_events'",
            Integer.class);
        assertEquals(1, count);
    }

    @Test
    void 用例2_tasks表新增due_time和source_event_id列() {
        Integer dueTimeCol = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME) = 'tasks' AND LOWER(COLUMN_NAME) = 'due_time'",
            Integer.class);
        assertEquals(1, dueTimeCol);
        Integer sourceEventCol = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME) = 'tasks' AND LOWER(COLUMN_NAME) = 'source_event_id'",
            Integer.class);
        assertEquals(1, sourceEventCol);
    }

    @Test
    void 用例3_互斥约束_一次性与重复不能同时设置() {
        // event_date 和 recurrence 同时非空应违反约束
        assertThrows(Exception.class, () -> jdbcTemplate.update(
            "INSERT INTO calendar_events (family_id, title, event_time, event_date, recurrence) " +
            "VALUES (1, '测试', '10:00', '2026-09-10', '{\"freq\":\"daily\"}')"));
    }

    @Test
    void 用例4_时间格式约束() {
        assertThrows(Exception.class, () -> jdbcTemplate.update(
            "INSERT INTO calendar_events (family_id, title, event_time, event_date) " +
            "VALUES (1, '测试', '25:99', '2026-09-10')"));
    }
}
```

- [ ] **Step 6: 运行测试验证**

Run: `wsl -d Ubuntu bash -lc "cd /home/mayh/code/family-app/backend && ./gradlew test --tests 'com.familyapp.V11MigrationTest' --no-daemon -q"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V11__Add_Calendar_Events.sql
git add backend/src/main/resources/db/migration/afterMigrate__agent_reader.sql
git add backend/src/main/java/com/familyapp/calendar/
git add backend/src/test/java/com/familyapp/V11MigrationTest.java
git commit -m "feat(calendar): V11迁移 + CalendarEvent实体 + Repository"
```

---

### Task 2: Task 实体扩展 + TaskSource.CALENDAR + TaskResponse 更新

**Files:**
- Modify: `backend/src/main/java/com/familyapp/task/Task.java`（加 dueTime、sourceEventId 字段）
- Modify: `backend/src/main/java/com/familyapp/task/TaskSource.java`（加 CALENDAR）
- Modify: `backend/src/main/java/com/familyapp/task/dto/TaskResponse.java`（加 dueTime、sourceEventId）
- Test: `backend/src/test/java/com/familyapp/task/TaskCalendarFieldsTest.java`

**Interfaces:**
- Consumes: V11 迁移已加 due_time、source_event_id 列
- Produces: `Task.getDueTime()`/`setDueTime(String)`、`Task.getSourceEventId()`/`setSourceEventId(Long)`、`TaskSource.CALENDAR`、`TaskResponse.dueTime()`/`sourceEventId()`

- [ ] **Step 1: 写失败测试**

```java
package com.familyapp.task;

import com.familyapp.task.dto.TaskResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Change 13: Task 实体新增 dueTime/sourceEventId 字段验证
 */
class TaskCalendarFieldsTest {

    @Test
    void task_应有dueTime和sourceEventId字段() {
        Task task = new Task();
        task.setDueTime("10:00");
        task.setSourceEventId(42L);
        assertThat(task.getDueTime()).isEqualTo("10:00");
        assertThat(task.getSourceEventId()).isEqualTo(42L);
    }

    @Test
    void taskSource_应包含CALENDAR() {
        assertThat(TaskSource.valueOf("CALENDAR")).isEqualTo(TaskSource.CALENDAR);
    }

    @Test
    void taskResponse_应包含dueTime和sourceEventId() {
        Task task = new Task();
        task.setTitle("周会");
        task.setDueTime("10:00");
        task.setSourceEventId(7L);
        task.setFamilyId(1L);
        TaskResponse resp = TaskResponse.fromEntity(task);
        assertThat(resp.dueTime()).isEqualTo("10:00");
        assertThat(resp.sourceEventId()).isEqualTo(7L);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `wsl -d Ubuntu bash -lc "cd /home/mayh/code/family-app/backend && ./gradlew test --tests 'com.familyapp.task.TaskCalendarFieldsTest' --no-daemon -q"`
Expected: FAIL（编译错误，字段不存在）

- [ ] **Step 3: 实现 Task 实体扩展**

在 `Task.java` 中 `targetTemplateVersionId` 字段后追加：

```java
    /** Change 13: 日程任务的具体时间点 HH:mm（可空） */
    @Column(name = "due_time", length = 5)
    private String dueTime;

    /** Change 13: 来源日程 ID（可空，非日程任务为 NULL） */
    @Column(name = "source_event_id")
    private Long sourceEventId;
```

及对应 getter/setter：

```java
    public String getDueTime() { return dueTime; }
    public void setDueTime(String dueTime) { this.dueTime = dueTime; }
    public Long getSourceEventId() { return sourceEventId; }
    public void setSourceEventId(Long sourceEventId) { this.sourceEventId = sourceEventId; }
```

- [ ] **Step 4: TaskSource 加 CALENDAR**

```java
public enum TaskSource {
    MANUAL,
    AGENT,
    ROUTINE,
    SCHEDULE,
    TRIGGER,
    CALENDAR
}
```

- [ ] **Step 5: TaskResponse 加字段**

在 record 参数列表 `Instant updatedAt` 后追加 `String dueTime, Long sourceEventId`，`fromEntity` 中追加 `t.getDueTime(), t.getSourceEventId()`。

- [ ] **Step 6: 运行测试确认通过**

Run: `wsl -d Ubuntu bash -lc "cd /home/mayh/code/family-app/backend && ./gradlew test --tests 'com.familyapp.task.TaskCalendarFieldsTest' --no-daemon -q"`
Expected: PASS

- [ ] **Step 7: 运行全量测试确认无回归**

Run: `wsl -d Ubuntu bash -lc "cd /home/mayh/code/family-app/backend && ./gradlew test --no-daemon -q"`
Expected: 全绿（基线 263 + 新增 3）

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/familyapp/task/Task.java
git add backend/src/main/java/com/familyapp/task/TaskSource.java
git add backend/src/main/java/com/familyapp/task/dto/TaskResponse.java
git add backend/src/test/java/com/familyapp/task/TaskCalendarFieldsTest.java
git commit -m "feat(calendar): Task实体扩展dueTime/sourceEventId + TaskSource.CALENDAR"
```

---

### Task 3: CalendarEventService（CRUD + 校验 + 今日展开）

**Files:**
- Create: `backend/src/main/java/com/familyapp/calendar/CalendarEventService.java`
- Create: `backend/src/main/java/com/familyapp/calendar/dto/CreateCalendarEventRequest.java`
- Create: `backend/src/main/java/com/familyapp/calendar/dto/UpdateCalendarEventRequest.java`
- Create: `backend/src/main/java/com/familyapp/calendar/dto/CalendarEventResponse.java`
- Create: `backend/src/main/java/com/familyapp/calendar/dto/TodayEventItem.java`
- Test: `backend/src/test/java/com/familyapp/calendar/CalendarEventServiceTest.java`

**Interfaces:**
- Consumes: `CalendarEventRepository`、`UserContext.current()`、`ResultException`
- Produces: `CalendarEventService.create(req)`、`.update(id, req)`、`.delete(id)`、`.list(includeInactive)`、`.get(id)`、`.todayEvents()` → `List<TodayEventItem>`

- [ ] **Step 1: 写 DTO 类**

`CreateCalendarEventRequest`:
```java
package com.familyapp.calendar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public class CreateCalendarEventRequest {
    @NotBlank(message = "日程标题不能为空")
    @Size(max = 200, message = "日程标题超过200字")
    private String title;
    private String description;
    private LocalDate eventDate;
    @NotBlank(message = "请填写具体时间")
    @Pattern(regexp = "^\\d{2}:\\d{2}$", message = "时间格式不正确，应为 HH:mm")
    private String eventTime;
    /** 重复规则 JSON 字符串：{"freq":"daily"} / {"freq":"weekly","weekdays":[2]} / {"freq":"monthly","day":15} */
    private String recurrence;
    private Boolean reminderEnabled;
    private Long assigneeProfileId;
    private Long categoryId;
    // getter/setter
}
```

`UpdateCalendarEventRequest`: 同 Create 但所有字段可空（PATCH 语义）。

`CalendarEventResponse`:
```java
public record CalendarEventResponse(
    Long id, String title, String description,
    LocalDate eventDate, String eventTime, String recurrence,
    Boolean reminderEnabled, Long assigneeProfileId, Long categoryId,
    Boolean isActive, Instant createdAt, Instant updatedAt
) { /* fromEntity 静态工厂 */ }
```

`TodayEventItem`:
```java
public record TodayEventItem(Long eventId, String title, String eventTime, Long assigneeProfileId) {}
```

- [ ] **Step 2: 写失败测试**

```java
package com.familyapp.calendar;

// 纯 Mockito 测试，覆盖：
// 1. create 成功（一次性）
// 2. create 成功（重复）
// 3. create 失败：标题为空 → ResultException
// 4. create 失败：时间格式错误 → ResultException
// 5. create 失败：eventDate 和 recurrence 同时设置 → ResultException
// 6. update 成功
// 7. delete 软删
// 8. todayEvents 展开重复规则命中今日
// 9. CHILD 角色写操作 → ResultException 40302
```

（完整测试代码在实现时按上述 9 个用例编写，使用 mock CalendarEventRepository + UserContext 静态 mock）

- [ ] **Step 3: 运行测试确认失败**

- [ ] **Step 4: 实现 CalendarEventService**

核心逻辑：
- `create`: 校验标题/时间/互斥 → 解析 recurrence JSON → 保存
- `update`: 查找 → 校验 → 更新非空字段
- `delete`: 软删（设 deletedAt）
- `list`: 按 familyId 查，可选含 inactive
- `get`: 按 id + familyId 查
- `todayEvents`: 查 active 日程 → 匹配今日（一次性看 eventDate==today，重复看 recurrence）→ 按 eventTime 排序返回
- 权限：写操作前检查 `UserContext.current().role()`，CHILD 抛 `ResultException(40302, "孩子成员不能创建或修改日程")`

重复匹配逻辑（`matchesToday` 私有方法）：
```java
private boolean matchesToday(CalendarEvent event, LocalDate today) {
    if (event.getRecurrence() == null) {
        return today.equals(event.getEventDate());
    }
    String freq = event.getRecurrence().path("freq").asText("");
    return switch (freq) {
        case "daily" -> true;
        case "weekly" -> {
            int isoDay = today.getDayOfWeek().getValue();
            for (JsonNode d : event.getRecurrence().path("weekdays")) {
                if (d.asInt() == isoDay) yield true;
            }
            yield false;
        }
        case "monthly" -> event.getRecurrence().path("day").asInt(-1) == today.getDayOfMonth();
        default -> false;
    };
}
```

- [ ] **Step 5: 运行测试确认通过**

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/familyapp/calendar/
git add backend/src/test/java/com/familyapp/calendar/
git commit -m "feat(calendar): CalendarEventService CRUD + 校验 + 今日展开"
```

---

### Task 4: CalendarController（API 端点 E-1~E-6）

**Files:**
- Create: `backend/src/main/java/com/familyapp/calendar/CalendarController.java`
- Test: `backend/src/test/java/com/familyapp/calendar/CalendarControllerTest.java`

**Interfaces:**
- Consumes: `CalendarEventService`
- Produces: REST 端点 `/api/calendar/events`（GET/POST）、`/api/calendar/events/{id}`（GET/PUT/DELETE）、`/api/calendar/today`（GET）

- [ ] **Step 1: 写失败测试（MockMvc）**

```java
// 使用 @SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("test")
// TestUserContextFilter 注入角色（X-Test-Role 头）
// 覆盖：
// 1. POST /api/calendar/events 创建成功 → 201
// 2. GET /api/calendar/events 列表
// 3. GET /api/calendar/events/{id} 详情
// 4. PUT /api/calendar/events/{id} 更新
// 5. DELETE /api/calendar/events/{id} 软删
// 6. GET /api/calendar/today 今日展开
// 7. CHILD 角色 POST → 403
// 8. 标题为空 → 400 + 中文消息
```

- [ ] **Step 2: 运行测试确认失败**

- [ ] **Step 3: 实现 CalendarController**

```java
package com.familyapp.calendar;

import com.familyapp.calendar.dto.*;
import com.familyapp.common.Result;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 日程管理 API（Change 13）
 */
@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

    private final CalendarEventService service;

    public CalendarController(CalendarEventService service) {
        this.service = service;
    }

    @GetMapping("/events")
    public Result<List<CalendarEventResponse>> list(
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
        return Result.ok(service.list(includeInactive));
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<CalendarEventResponse> create(@Valid @RequestBody CreateCalendarEventRequest req) {
        return Result.ok(service.create(req));
    }

    @GetMapping("/events/{id}")
    public Result<CalendarEventResponse> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    @PutMapping("/events/{id}")
    public Result<CalendarEventResponse> update(@PathVariable Long id,
                                                 @Valid @RequestBody UpdateCalendarEventRequest req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/events/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok(true);
    }

    @GetMapping("/today")
    public Result<List<TodayEventItem>> today() {
        return Result.ok(service.todayEvents());
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

- [ ] **Step 5: 全量测试无回归**

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/familyapp/calendar/CalendarController.java
git add backend/src/test/java/com/familyapp/calendar/CalendarControllerTest.java
git commit -m "feat(calendar): CalendarController API端点 E-1~E-6"
```

---

### Task 5: CalendarSchedulerJobs（每日生成任务）

**Files:**
- Create: `backend/src/main/java/com/familyapp/calendar/CalendarSchedulerJobs.java`
- Test: `backend/src/test/java/com/familyapp/calendar/CalendarSchedulerJobsTest.java`

**Interfaces:**
- Consumes: `CalendarEventRepository.findDistinctFamilyIdsWithActiveEvents()`、`CalendarEventService.matchesToday()`（需提取为包可见方法或独立工具）、`TaskRepository`
- Produces: 每日 02:30 为命中今日的日程生成 PENDING 任务（带 dueTime + sourceEventId + source=CALENDAR）

- [ ] **Step 1: 写失败测试**

```java
// 纯 Mockito 测试，覆盖：
// 1. weekly 周二命中 → 生成任务（dueTime/sourceEventId/source=CALENDAR 正确）
// 2. weekly 非命中日 → 不生成
// 3. monthly 命中/不命中
// 4. daily 每天命中
// 5. 一次性事件 eventDate==today → 生成
// 6. 一次性事件 eventDate!=today → 不生成
// 7. 停用日程不生成
// 8. 幂等：已存在同 sourceEventId+dueDate 的任务 → 跳过（DataAccessException 捕获）
// 9. 单家庭异常不阻断其他家庭
```

- [ ] **Step 2: 运行测试确认失败**

- [ ] **Step 3: 实现 CalendarSchedulerJobs**

```java
package com.familyapp.calendar;

import com.familyapp.task.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Change 13 日程调度器：每日 02:30 扫描启用日程，匹配今日重复规则，生成 PENDING 任务。
 * 逐家庭隔离，幂等靠 (source_event_id, due_date) 唯一索引兜底。
 */
@Component
public class CalendarSchedulerJobs {

    private static final Logger log = LoggerFactory.getLogger(CalendarSchedulerJobs.class);

    private final CalendarEventRepository eventRepository;
    private final TaskRepository taskRepository;
    private final CalendarRecurrenceMatcher matcher;

    public CalendarSchedulerJobs(CalendarEventRepository eventRepository,
                                  TaskRepository taskRepository,
                                  CalendarRecurrenceMatcher matcher) {
        this.eventRepository = eventRepository;
        this.taskRepository = taskRepository;
        this.matcher = matcher;
    }

    @Scheduled(cron = "0 30 2 * * ?")
    public void generateTodayTasks() {
        LocalDate today = LocalDate.now();
        List<Long> familyIds = eventRepository.findDistinctFamilyIdsWithActiveEvents();
        log.info("日程调度开始，共 {} 个家庭", familyIds.size());
        for (Long familyId : familyIds) {
            try {
                executeForFamily(familyId, today);
            } catch (Exception e) {
                log.error("家庭[{}]日程调度失败，隔离继续：{}", familyId, e.getMessage());
            }
        }
    }

    /** 单家庭执行（可测试入口） */
    public void executeForFamily(Long familyId, LocalDate today) {
        List<CalendarEvent> events = eventRepository.findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(familyId);
        for (CalendarEvent event : events) {
            if (!matcher.matchesToday(event, today)) {
                continue;
            }
            try {
                createTaskFromEvent(event, today);
            } catch (DataAccessException e) {
                // 唯一索引冲突 = 已生成过，幂等跳过
                log.debug("日程[{}]今日任务已存在，跳过", event.getId());
            }
        }
    }

    private void createTaskFromEvent(CalendarEvent event, LocalDate today) {
        Task task = new Task();
        task.setFamilyId(event.getFamilyId());
        task.setTitle(event.getTitle());
        task.setDescription(event.getDescription());
        task.setDueDate(today);
        task.setDueTime(event.getEventTime());
        task.setSourceEventId(event.getId());
        task.setSource(TaskSource.CALENDAR);
        task.setStatus(TaskStatus.PENDING);
        task.setPriority(TaskPriority.MEDIUM);
        task.setAssigneeProfileId(event.getAssigneeProfileId());
        task.setCategoryId(event.getCategoryId());
        task.setCreatedBy(event.getCreatedBy());
        taskRepository.save(task);
    }
}
```

- [ ] **Step 4: 提取 CalendarRecurrenceMatcher**

```java
package com.familyapp.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 日程重复规则匹配器（Change 13）：判断日程是否命中指定日期。
 */
@Component
public class CalendarRecurrenceMatcher {

    public boolean matchesToday(CalendarEvent event, LocalDate date) {
        if (event.getRecurrence() == null) {
            return date.equals(event.getEventDate());
        }
        String freq = event.getRecurrence().path("freq").asText("");
        return switch (freq) {
            case "daily" -> true;
            case "weekly" -> weeklyHit(event.getRecurrence(), date);
            case "monthly" -> monthlyHit(event.getRecurrence(), date);
            default -> false;
        };
    }

    private boolean weeklyHit(JsonNode recurrence, LocalDate date) {
        int isoDay = date.getDayOfWeek().getValue();
        for (JsonNode d : recurrence.path("weekdays")) {
            if (d.asInt() == isoDay) return true;
        }
        return false;
    }

    private boolean monthlyHit(JsonNode recurrence, LocalDate date) {
        return recurrence.path("day").asInt(-1) == date.getDayOfMonth();
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/familyapp/calendar/CalendarSchedulerJobs.java
git add backend/src/main/java/com/familyapp/calendar/CalendarRecurrenceMatcher.java
git add backend/src/test/java/com/familyapp/calendar/CalendarSchedulerJobsTest.java
git commit -m "feat(calendar): CalendarSchedulerJobs 每日02:30生成任务 + 幂等"
```

---

### Task 6: CalendarReminderJobs（微信早报）

**Files:**
- Create: `backend/src/main/java/com/familyapp/calendar/CalendarReminderJobs.java`
- Modify: `backend/src/main/java/com/familyapp/wechat/WechatNotifyService.java`（新增 `notifyEventDigest` 方法）
- Test: `backend/src/test/java/com/familyapp/calendar/CalendarReminderJobsTest.java`

**Interfaces:**
- Consumes: `CalendarEventRepository`、`WechatNotifyService`、`WechatBindRepository`、`FamilyMemberRepository`、`PersonProfileRepository`
- Produces: 每日 07:30 推送今日日程早报到微信

- [ ] **Step 1: 写失败测试**

```java
// 覆盖：
// 1. 有提醒日程 + 有执行人 + 执行人已绑定微信 → 推给执行人
// 2. 有提醒日程 + 无执行人 → 推给家庭全员已绑定成员
// 3. 无提醒日程 → 不推送
// 4. 模板 ID 留空 → 静默跳过
// 5. 异常隔离：单家庭失败不阻断
```

- [ ] **Step 2: 运行测试确认失败**

- [ ] **Step 3: 实现**

在 `WechatNotifyService` 中新增：

```java
/**
 * Change 13: 日程早报推送。接收人由调用方决定（执行人或全员）。
 */
public void notifyEventDigest(List<String> openids, int eventCount, String eventSummary) {
    if (openids.isEmpty() || eventCount == 0) return;
    try {
        Map<String, Object> params = new HashMap<>();
        params.put("event_count", eventCount);
        params.put("event_summary", eventSummary);
        notifyClient.sendMessages(buildMessages(openids, "event_digest", params));
    } catch (Exception e) {
        log.warn("日程早报推送失败（已隔离）：{}", e.getMessage());
    }
}
```

`CalendarReminderJobs`:
```java
@Component
public class CalendarReminderJobs {
    // 每日 07:30
    @Scheduled(cron = "0 30 7 * * ?")
    public void sendDailyEventDigest() {
        // 1. 查有启用提醒日程的家庭
        // 2. 逐家庭：匹配今日日程 → 组装摘要
        // 3. 确定接收人：有 assigneeProfileId → 查对应 userId → 查 openid；无 → 全员
        // 4. 调 wechatNotifyService.notifyEventDigest(...)
        // 5. 逐家庭 try/catch 隔离
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/familyapp/calendar/CalendarReminderJobs.java
git add backend/src/main/java/com/familyapp/wechat/WechatNotifyService.java
git add backend/src/test/java/com/familyapp/calendar/CalendarReminderJobsTest.java
git commit -m "feat(calendar): CalendarReminderJobs 每日07:30微信日程早报"
```

---

### Task 7: 前端 API 层 + CalendarView 页面

**Files:**
- Create: `frontend/src/api/calendar.js`
- Create: `frontend/src/views/CalendarView.vue`
- Test: `frontend/src/tests/api/calendar.test.js`

**Interfaces:**
- Consumes: `frontend/src/api/client.js`（axios 实例）
- Produces: `calendarApi.list()`、`.create(payload)`、`.get(id)`、`.update(id, payload)`、`.del(id)`、`.today()`

- [ ] **Step 1: 写 API 层测试**

```javascript
import { describe, it, expect, beforeEach, vi } from 'vitest'
import client from '../../api/client'
import { calendarApi } from '../../api/calendar'

vi.mock('../../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }
}))

describe('calendarApi mock 层', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('list: GET /calendar/events', async () => {
    vi.mocked(client.get).mockResolvedValueOnce([])
    const r = await calendarApi.list()
    expect(r).toEqual([])
    expect(client.get).toHaveBeenCalledWith('/calendar/events')
  })

  it('create: POST /calendar/events', async () => {
    const payload = { title: '周会', eventTime: '10:00', recurrence: '{"freq":"weekly","weekdays":[2]}' }
    vi.mocked(client.post).mockResolvedValueOnce({ id: 1 })
    const r = await calendarApi.create(payload)
    expect(r.id).toBe(1)
    expect(client.post).toHaveBeenCalledWith('/calendar/events', payload)
  })

  it('today: GET /calendar/today', async () => {
    vi.mocked(client.get).mockResolvedValueOnce([{ title: '周会', eventTime: '10:00' }])
    const r = await calendarApi.today()
    expect(r).toHaveLength(1)
    expect(client.get).toHaveBeenCalledWith('/calendar/today')
  })

  it('del: DELETE /calendar/events/:id', async () => {
    vi.mocked(client.delete).mockResolvedValueOnce(true)
    const r = await calendarApi.del(3)
    expect(r).toBe(true)
    expect(client.delete).toHaveBeenCalledWith('/calendar/events/3')
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `wsl -d Ubuntu bash -lc "cd /home/mayh/code/family-app/frontend && npx vitest run src/tests/api/calendar.test.js"`
Expected: FAIL（模块不存在）

- [ ] **Step 3: 实现 API 层**

```javascript
import client from './client'

export const calendarApi = {
  list:   (includeInactive) => client.get('/calendar/events', { params: includeInactive ? { includeInactive: true } : {} }),
  create: (payload)         => client.post('/calendar/events', payload),
  get:    (id)              => client.get(`/calendar/events/${id}`),
  update: (id, payload)     => client.put(`/calendar/events/${id}`, payload),
  del:    (id)              => client.delete(`/calendar/events/${id}`),
  today:  ()                => client.get('/calendar/today')
}

export default calendarApi
```

- [ ] **Step 4: 运行测试确认通过**

- [ ] **Step 5: 实现 CalendarView.vue**

老人易用设计要点：
- 大字号（标题 24px+，正文 18px+）
- 大按钮（min-height 56px）
- 中文标签 + emoji 图标
- 重复规则用大按钮单选（不重复/每天/每周/每月）
- 每周 → 星期多选（一~日 7 个大按钮）
- 每月 → 几号数字输入
- 删除需 ElMessageBox.confirm 二次确认
- 表单校验失败用中文提示

（完整 Vue SFC 代码约 300 行，含 template/script/style 三段）

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/calendar.js
git add frontend/src/views/CalendarView.vue
git add frontend/src/tests/api/calendar.test.js
git commit -m "feat(calendar): 前端API层 + CalendarView日程管理页面"
```

---

### Task 8: 前端集成（路由 + 入口 + 首页卡片 + 任务角标）

**Files:**
- Modify: `frontend/src/router/index.js`（加 calendar 路由）
- Modify: `frontend/src/views/MoreMenuView.vue`（加日程入口）
- Modify: `frontend/src/views/DashboardView.vue`（加今日日程卡片）
- Modify: `frontend/src/components/TaskCardMobile.vue`（加 📅 角标）
- Modify: `frontend/src/components/AppSider.vue`（加日程菜单项）
- Test: `frontend/src/tests/views/CalendarRoute.test.js`

**Interfaces:**
- Consumes: `calendarApi.today()`、`CalendarView.vue`
- Produces: `/calendar` 路由可访问、更多菜单有入口、首页有今日日程卡片、日程任务有角标

- [ ] **Step 1: 写路由测试**

```javascript
// 验证 /calendar 路由存在且指向 CalendarView
// 验证 MoreMenuView entries 包含 { path: '/calendar', icon: '📅', label: '日程' }
```

- [ ] **Step 2: 运行测试确认失败**

- [ ] **Step 3: 实现路由 + 入口**

`router/index.js` children 中追加：
```javascript
{ path: 'calendar', name: 'calendar', component: () => import('../views/CalendarView.vue'), meta: { title: '日程', icon: '📅' } },
```

`MoreMenuView.vue` entries 数组追加：
```javascript
{ path: '/calendar', icon: '📅', label: '日程' }
```

`AppSider.vue` 追加菜单项：
```html
<el-menu-item index="/calendar">
  <span class="menu-icon">📅</span>
  <span class="menu-text">日程</span>
</el-menu-item>
```

- [ ] **Step 4: DashboardView 加今日日程卡片**

在「最近任务」卡片前插入：
```html
<el-card class="section-card">
  <template #header><span class="card-title">📅 今日日程</span></template>
  <div v-if="todayEvents.length === 0" class="card-desc">今天没有日程安排</div>
  <div v-else class="event-list">
    <div v-for="ev in todayEvents" :key="ev.eventId" class="event-item">
      <span class="event-time">{{ ev.eventTime }}</span>
      <span class="event-title">{{ ev.title }}</span>
    </div>
  </div>
</el-card>
```

script 中：
```javascript
import { calendarApi } from '@/api/calendar'
const todayEvents = ref([])
// onMounted 中：
try { todayEvents.value = (await calendarApi.today()) || [] } catch (e) { /* 静默 */ }
```

- [ ] **Step 5: TaskCardMobile 加 📅 角标**

在 `m-meta` div 中，`dueDate` 行后追加：
```html
<span v-if="task.source === 'CALENDAR'" class="meta-item">📅 日程</span>
```

- [ ] **Step 6: 运行测试确认通过**

- [ ] **Step 7: 前端构建验证**

Run: `wsl -d Ubuntu bash -lc "cd /home/mayh/code/family-app/frontend && npm run build"`
Expected: 无报错

- [ ] **Step 8: Commit**

```bash
git add frontend/src/router/index.js
git add frontend/src/views/MoreMenuView.vue
git add frontend/src/views/DashboardView.vue
git add frontend/src/components/TaskCardMobile.vue
git add frontend/src/components/AppSider.vue
git add frontend/src/tests/views/CalendarRoute.test.js
git commit -m "feat(calendar): 前端集成——路由/入口/首页卡片/任务角标"
```

---

## 验证清单（全部完成后）

- [ ] `./gradlew test` 全绿（后端）
- [ ] `npx vitest run` 全绿（前端）
- [ ] `npm run build` 无报错（前端）
- [ ] 更新 `.handoffs/current.md` 记录 Change 13 完成状态
