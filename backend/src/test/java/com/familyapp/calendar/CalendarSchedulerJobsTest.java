package com.familyapp.calendar;

import com.familyapp.task.Task;
import com.familyapp.task.TaskPriority;
import com.familyapp.task.TaskRepository;
import com.familyapp.task.TaskSource;
import com.familyapp.task.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Change 13 Task 5：CalendarSchedulerJobs 纯 Mockito 单元测试（不起 Spring 上下文）。
 *
 * <p>覆盖简报 Step 1 的 9 项：
 * <ol>
 *   <li>weekly 命中今日 → 生成任务（dueTime/sourceEventId/source=CALENDAR 等字段正确）</li>
 *   <li>weekly 非命中日 → 不生成</li>
 *   <li>monthly 命中 → 生成</li>
 *   <li>monthly 非命中 → 不生成</li>
 *   <li>daily 每天命中 → 生成</li>
 *   <li>一次性 eventDate==今日 → 生成</li>
 *   <li>一次性 eventDate!=今日 → 不生成</li>
 *   <li>停用日程：调度器只走 active-only 仓储口径，不生成</li>
 *   <li>幂等：save 撞唯一索引抛 DataAccessException → 被吞掉不外溢</li>
 *   <li>逐家庭隔离：单家庭异常不阻断其他家庭</li>
 * </ol>
 *
 * <p>时钟经 {@code Clock.fixed} 注入（沿用 CalendarEventServiceTest / RoutineMatcherTest 约定），
 * 「今日」为常量 {@link #TODAY}，重复规则命中日以动态 ISO 值构造，避免时间依赖型断言随日期漂移而失败。
 * {@code CalendarRecurrenceMatcher} 用真实实例（无状态），只 mock 两个仓储。
 */
class CalendarSchedulerJobsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 固定「今天」：2026-09-15，与 CalendarEventServiceTest 同口径 */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);
    private static final long FAMILY_ID = 10L;
    private static final long USER_ID = 1L;

    private CalendarEventRepository eventRepository;
    private TaskRepository taskRepository;
    private CalendarSchedulerJobs jobs;

    @BeforeEach
    void setUp() {
        eventRepository = mock(CalendarEventRepository.class);
        taskRepository = mock(TaskRepository.class);
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZONE).toInstant(), ZONE);
        jobs = new CalendarSchedulerJobs(
                eventRepository, taskRepository, new CalendarRecurrenceMatcher(), clock);
    }

    /** 构造启用中的日程夹具：recurrenceJson 为 null 表示一次性事件。 */
    private CalendarEvent event(Long id, LocalDate eventDate, String recurrenceJson) {
        CalendarEvent e = new CalendarEvent();
        e.setId(id);
        e.setFamilyId(FAMILY_ID);
        e.setTitle("日程" + id);
        e.setDescription("描述" + id);
        e.setEventTime("08:30");
        e.setEventDate(eventDate);
        if (recurrenceJson != null) {
            try {
                e.setRecurrence(MAPPER.readTree(recurrenceJson));
            } catch (Exception ex) {
                throw new IllegalStateException("测试夹具 JSON 非法", ex);
            }
        }
        e.setIsActive(true);
        e.setReminderEnabled(true);
        e.setCreatedBy(USER_ID);
        e.setAssigneeProfileId(7L);
        e.setCategoryId(3L);
        return e;
    }

    private void stubActiveEvents(CalendarEvent... events) {
        when(eventRepository.findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(FAMILY_ID))
                .thenReturn(List.of(events));
    }

    // 用例 1：weekly 命中今日 → 生成任务，字段映射逐一对齐简报 createTaskFromEvent
    @Test
    void weekly_hit_generates_calendar_task_with_mapped_fields() {
        int isoDay = TODAY.getDayOfWeek().getValue();
        CalendarEvent ev = event(100L, null, "{\"freq\":\"weekly\",\"weekdays\":[" + isoDay + "]}");
        stubActiveEvents(ev);

        jobs.executeForFamily(FAMILY_ID, TODAY);

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(captor.capture());
        Task t = captor.getValue();
        assertThat(t.getFamilyId()).isEqualTo(FAMILY_ID);
        assertThat(t.getSourceEventId()).isEqualTo(100L);
        assertThat(t.getDueDate()).isEqualTo(TODAY);
        assertThat(t.getDueTime()).isEqualTo("08:30");
        assertThat(t.getSource()).isEqualTo(TaskSource.CALENDAR);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(t.getPriority()).isEqualTo(TaskPriority.MEDIUM);
        assertThat(t.getTitle()).isEqualTo("日程100");
        assertThat(t.getDescription()).isEqualTo("描述100");
        assertThat(t.getAssigneeProfileId()).isEqualTo(7L);
        assertThat(t.getCategoryId()).isEqualTo(3L);
        assertThat(t.getCreatedBy()).isEqualTo(USER_ID);
    }

    // 用例 2：weekly 非命中日 → 不生成
    @Test
    void weekly_miss_generates_nothing() {
        int isoDay = TODAY.getDayOfWeek().getValue();
        int other = isoDay == 7 ? 1 : isoDay + 1;
        stubActiveEvents(event(101L, null, "{\"freq\":\"weekly\",\"weekdays\":[" + other + "]}"));

        jobs.executeForFamily(FAMILY_ID, TODAY);

        verify(taskRepository, never()).save(any());
    }

    // 用例 3：monthly 命中今日 → 生成
    @Test
    void monthly_hit_generates_task() {
        stubActiveEvents(event(102L, null,
                "{\"freq\":\"monthly\",\"day\":" + TODAY.getDayOfMonth() + "}"));

        jobs.executeForFamily(FAMILY_ID, TODAY);

        verify(taskRepository).save(any(Task.class));
    }

    // 用例 4：monthly 非命中 → 不生成
    @Test
    void monthly_miss_generates_nothing() {
        int missDay = TODAY.getDayOfMonth() <= 3 ? 5 : 1;
        stubActiveEvents(event(107L, null, "{\"freq\":\"monthly\",\"day\":" + missDay + "}"));

        jobs.executeForFamily(FAMILY_ID, TODAY);

        verify(taskRepository, never()).save(any());
    }

    // 用例 5：daily 每天命中 → 生成
    @Test
    void daily_recurrence_hits_every_day() {
        stubActiveEvents(event(103L, null, "{\"freq\":\"daily\"}"));

        jobs.executeForFamily(FAMILY_ID, TODAY);

        verify(taskRepository).save(any(Task.class));
    }

    // 用例 6：一次性 eventDate==今日 → 生成
    @Test
    void one_time_event_matching_today_generates_task() {
        stubActiveEvents(event(104L, TODAY, null));

        jobs.executeForFamily(FAMILY_ID, TODAY);

        verify(taskRepository).save(any(Task.class));
    }

    // 用例 7：一次性 eventDate!=今日 → 不生成
    @Test
    void one_time_event_other_day_generates_nothing() {
        stubActiveEvents(event(105L, TODAY.plusDays(1), null));

        jobs.executeForFamily(FAMILY_ID, TODAY);

        verify(taskRepository, never()).save(any());
    }

    // 用例 8：停用日程 —— 调度器只走 active-only 仓储口径，includeInactive 方法绝不触达
    @Test
    void scheduler_uses_active_only_query_and_skips_inactive() {
        // 停用日程已由 active-only 方法在源头过滤，返回空 → 不生成
        stubActiveEvents();

        jobs.executeForFamily(FAMILY_ID, TODAY);

        verify(eventRepository).findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(FAMILY_ID);
        verify(eventRepository, never()).findByFamilyIdAndDeletedAtIsNull(any());
        verify(taskRepository, never()).save(any());
    }

    // 用例 9：幂等 —— save 撞 uq_tasks_event_date 抛 DataAccessException → 被吞掉，不外溢
    @Test
    void idempotent_conflict_on_save_is_swallowed() {
        stubActiveEvents(event(106L, null, "{\"freq\":\"daily\"}"));
        when(taskRepository.save(any(Task.class)))
                .thenThrow(new DuplicateKeyException("uq_tasks_event_date"));

        // 不抛异常即为通过：唯一索引冲突被视为「今日已生成」，幂等跳过
        jobs.executeForFamily(FAMILY_ID, TODAY);

        verify(taskRepository).save(any(Task.class));
    }

    // 用例 10：逐家庭隔离 —— 单家庭抛异常不阻断其他家庭（generateTodayTasks 走注入时钟取今日）
    @Test
    void one_family_failure_does_not_block_others() {
        when(eventRepository.findDistinctFamilyIdsWithActiveEvents())
                .thenReturn(List.of(1L, 2L));
        when(eventRepository.findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(1L))
                .thenThrow(new RuntimeException("db down"));
        // daily 命中任意日期，保证 family2 无论今日为何都会生成
        CalendarEvent family2Event = event(200L, null, "{\"freq\":\"daily\"}");
        family2Event.setFamilyId(2L);
        when(eventRepository.findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(eq(2L)))
                .thenReturn(List.of(family2Event));

        jobs.generateTodayTasks();

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().getFamilyId()).isEqualTo(2L);
    }
}
