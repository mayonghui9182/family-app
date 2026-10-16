package com.familyapp.calendar;

import com.familyapp.auth.UserContext;
import com.familyapp.calendar.dto.CalendarEventResponse;
import com.familyapp.calendar.dto.CreateCalendarEventRequest;
import com.familyapp.calendar.dto.TodayEventItem;
import com.familyapp.calendar.dto.UpdateCalendarEventRequest;
import com.familyapp.common.ResultException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Change 13 Task 3：CalendarEventService 纯 Mockito 单元测试（不起 Spring 上下文）。
 *
 * <p>用例分组：
 * <ul>
 *   <li>create：一次性 / 重复 / 47001 空标题 / 47002 时间格式 / 47003 互斥与双空 /
 *       47004 规则非法 JSON / 非 object / 取值白名单（freq 枚举、monthly.day、weekly.weekdays）</li>
 *   <li>update：PATCH 语义 / 空串 recurrence 视为未提供（缺陷回归）/ 一次性↔重复切换 /
 *       recurrence 补丁过白名单 / 47001 / 47003 / 47004 / 47005</li>
 *   <li>delete：软删不物理删 / 47005</li>
 *   <li>list / get：includeInactive 分支切换仓储方法、家庭隔离（跨家庭 id → 47005）</li>
 *   <li>todayEvents：daily/weekly/monthly/一次性展开与 eventTime 升序</li>
 *   <li>权限：CHILD 写 40302、CHILD 读放行</li>
 * </ul>
 *
 * <p>时钟经 {@code Clock.fixed} 注入（沿用 ClockConfig + RoutineMatcherTest 约定），
 * 「今日」为常量 {@link #TODAY}，避免时间依赖型断言随日期漂移而失败。
 */
class CalendarEventServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 固定「今天」：2026-09-15 */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);
    private static final long FAMILY_ID = 10L;
    private static final long USER_ID = 1L;

    private CalendarEventRepository repository;
    private CalendarEventService service;

    @BeforeEach
    void setUp() {
        repository = mock(CalendarEventRepository.class);
        service = new CalendarEventService(repository, Clock.fixed(TODAY.atStartOfDay(ZONE).toInstant(), ZONE));
        // 默认以 OWNER 身份登录（UserContext 基于 ThreadLocal，直接 setCurrent）
        UserContext.setCurrent(new UserContext.CurrentUser(
                USER_ID, "parent@example.com", "家长", FAMILY_ID, "OWNER"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private CreateCalendarEventRequest createRequest(String title, String time,
                                                     LocalDate date, String recurrence) {
        CreateCalendarEventRequest req = new CreateCalendarEventRequest();
        req.setTitle(title);
        req.setEventTime(time);
        req.setEventDate(date);
        req.setRecurrence(recurrence);
        return req;
    }

    private CalendarEvent event(Long id, LocalDate eventDate, String recurrenceJson) {
        CalendarEvent e = new CalendarEvent();
        e.setId(id);
        e.setFamilyId(FAMILY_ID);
        e.setTitle("日程" + id);
        e.setEventTime("09:00");
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
        return e;
    }

    private void stubSaveReturnsArgument() {
        when(repository.save(any(CalendarEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================== create ====================

    // 用例 1：create 成功（一次性）—— 字段落库 + familyId/createdBy 来自 UserContext
    @Test
    void create_one_time_event_succeeds() {
        stubSaveReturnsArgument();

        CreateCalendarEventRequest req = createRequest(
                "家庭聚餐", "18:30", LocalDate.of(2026, 9, 15), null);
        req.setDescription("带上蛋糕");
        req.setReminderEnabled(true);
        req.setAssigneeProfileId(7L);
        req.setCategoryId(3L);

        CalendarEventResponse resp = service.create(req);

        ArgumentCaptor<CalendarEvent> captor = ArgumentCaptor.forClass(CalendarEvent.class);
        verify(repository).save(captor.capture());
        CalendarEvent saved = captor.getValue();
        assertThat(saved.getFamilyId()).isEqualTo(FAMILY_ID);
        assertThat(saved.getCreatedBy()).isEqualTo(USER_ID);
        assertThat(saved.getTitle()).isEqualTo("家庭聚餐");
        assertThat(saved.getEventTime()).isEqualTo("18:30");
        assertThat(saved.getEventDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(saved.getRecurrence()).isNull();
        assertThat(saved.getDescription()).isEqualTo("带上蛋糕");
        assertThat(saved.getAssigneeProfileId()).isEqualTo(7L);
        assertThat(saved.getCategoryId()).isEqualTo(3L);
        assertThat(saved.getReminderEnabled()).isTrue();
        assertThat(saved.getIsActive()).isTrue();
        assertThat(saved.getDeletedAt()).isNull();

        assertThat(resp.title()).isEqualTo("家庭聚餐");
        assertThat(resp.eventDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(resp.eventTime()).isEqualTo("18:30");
        assertThat(resp.recurrence()).isNull();
    }

    // 用例 2：create 成功（重复）—— recurrence JSON 字符串解析为 JsonNode 存储
    @Test
    void create_recurring_event_parses_recurrence_json() {
        stubSaveReturnsArgument();

        CreateCalendarEventRequest req = createRequest(
                "倒垃圾", "20:00", null, "{\"freq\":\"weekly\",\"weekdays\":[2,4]}");

        CalendarEventResponse resp = service.create(req);

        ArgumentCaptor<CalendarEvent> captor = ArgumentCaptor.forClass(CalendarEvent.class);
        verify(repository).save(captor.capture());
        CalendarEvent saved = captor.getValue();
        assertThat(saved.getEventDate()).isNull();
        assertThat(saved.getRecurrence()).isNotNull();
        assertThat(saved.getRecurrence().path("freq").asText()).isEqualTo("weekly");
        assertThat(saved.getRecurrence().path("weekdays").size()).isEqualTo(2);

        assertThat(resp.eventDate()).isNull();
        assertThat(resp.recurrence()).contains("weekly");
    }

    // 用例 3：标题为空 → 47001，不落库
    @Test
    void create_with_blank_title_throws_47001() {
        CreateCalendarEventRequest req = createRequest(
                "  ", "10:00", LocalDate.of(2026, 9, 15), null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> {
                    ResultException re = (ResultException) ex;
                    assertThat(re.getCode()).isEqualTo(47001);
                    assertThat(re.getMessage()).isEqualTo("日程标题不能为空");
                });
        verify(repository, never()).save(any());
    }

    // 用例 4：时间格式错误 → 47002，不落库
    @Test
    void create_with_invalid_time_format_throws_47002() {
        CreateCalendarEventRequest req = createRequest(
                "晨练", "9:00", LocalDate.of(2026, 9, 15), null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> {
                    ResultException re = (ResultException) ex;
                    assertThat(re.getCode()).isEqualTo(47002);
                    assertThat(re.getMessage()).isEqualTo("日程时间格式不正确");
                });
        verify(repository, never()).save(any());
    }

    // 用例 5：eventDate 与 recurrence 同时设置 → 47003，不落库
    @Test
    void create_with_both_date_and_recurrence_throws_47003() {
        CreateCalendarEventRequest req = createRequest(
                "冲突日程", "10:00",
                LocalDate.of(2026, 9, 15), "{\"freq\":\"daily\"}");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> {
                    ResultException re = (ResultException) ex;
                    assertThat(re.getCode()).isEqualTo(47003);
                    assertThat(re.getMessage()).isEqualTo("一次性日期与重复规则不能同时设置");
                });
        verify(repository, never()).save(any());
    }

    // 用例 5.1：日期与规则都为空 → 47003，且文案给出可操作指引（老人友好，不能只说「不能同时设置」）
    @Test
    void create_with_neither_date_nor_recurrence_throws_47003_with_actionable_message() {
        CreateCalendarEventRequest req = createRequest("喝水", "10:00", null, null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> {
                    ResultException re = (ResultException) ex;
                    assertThat(re.getCode()).isEqualTo(47003);
                    assertThat(re.getMessage()).isEqualTo("请填写日程日期，或选择重复规则（每天/每周/每月）");
                });
        verify(repository, never()).save(any());
    }

    // 用例 5.2：recurrence 为空白串等同于未提供 → 与 eventDate==null 组合仍走双空分支 47003
    @Test
    void create_with_blank_recurrence_and_no_date_throws_47003() {
        CreateCalendarEventRequest req = createRequest("散步", "10:00", null, "   ");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> assertThat(((ResultException) ex).getCode()).isEqualTo(47003));
        verify(repository, never()).save(any());
    }

    // 用例 5.3：recurrence 非法 JSON → 47004，不落库
    @Test
    void create_with_malformed_recurrence_json_throws_47004() {
        CreateCalendarEventRequest req = createRequest("买菜", "10:00", null, "{freq:daily}");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> {
                    ResultException re = (ResultException) ex;
                    assertThat(re.getCode()).isEqualTo(47004);
                    assertThat(re.getMessage()).isEqualTo("重复规则格式不正确");
                });
        verify(repository, never()).save(any());
    }

    // 用例 5.4：recurrence 是合法 JSON 但非 object（数组）→ 47004
    @Test
    void create_with_non_object_recurrence_throws_47004() {
        CreateCalendarEventRequest req = createRequest("买菜", "10:00", null, "[\"daily\"]");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> assertThat(((ResultException) ex).getCode()).isEqualTo(47004));
        verify(repository, never()).save(any());
    }

    // ---------- 用例 5.5–5.11：recurrence 取值白名单（Change 13 终审 IMP-2 附带项） ----------
    // 修复前：parseRecurrence 只校验「是否为合法 JSON object」，字段取值一律放行。
    // 后果是 {"freq":"yearly"}、{"freq":"monthly","day":99} 都会返回「创建成功」，
    // 但 matcher 对它们恒不命中 —— 老人以为设了每月提醒，实际上永远不会有任何事发生。

    /** 断言：以该 recurrence 创建被拒（47004）且未触达仓储。 */
    private void assertCreateRejectedByRecurrenceWhitelist(String recurrenceJson) {
        CreateCalendarEventRequest req = createRequest("家庭日程", "09:00", null, recurrenceJson);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> assertThat(((ResultException) ex).getCode()).isEqualTo(47004));
        verify(repository, never()).save(any());
    }

    // 用例 5.5：freq 不在白名单 / 缺失 / 非字符串 → 47004
    @Test
    void create_with_unsupported_freq_throws_47004() {
        assertThatThrownBy(() -> service.create(
                createRequest("体检", "09:00", null, "{\"freq\":\"yearly\"}")))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> {
                    ResultException re = (ResultException) ex;
                    assertThat(re.getCode()).isEqualTo(47004);
                    assertThat(re.getMessage()).isEqualTo("重复规则不正确，请选择每天、每周或每月");
                });
        verify(repository, never()).save(any());

        assertCreateRejectedByRecurrenceWhitelist("{\"day\":15}");
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"\"}");
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"DAILY\"}");
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":123}");
    }

    // 用例 5.6：monthly 缺 day → 47004
    @Test
    void create_with_monthly_missing_day_throws_47004() {
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"monthly\"}");
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"monthly\",\"day\":null}");
    }

    // 用例 5.7：monthly 的 day 越界（0 / 负数 / 32 / 99）→ 47004
    // day=99 是最要紧的一条：钳制语义会把它当「当月最后一天」静默接受，必须在写入口就拒掉
    @Test
    void create_with_monthly_day_out_of_range_throws_47004() {
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"monthly\",\"day\":0}");
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"monthly\",\"day\":-1}");
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"monthly\",\"day\":32}");
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"monthly\",\"day\":99}");
    }

    // 用例 5.8：monthly 的 day 不是整数（字符串 / 小数）→ 47004
    @Test
    void create_with_monthly_day_not_integer_throws_47004() {
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"monthly\",\"day\":\"15\"}");
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"monthly\",\"day\":\"x\"}");
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"monthly\",\"day\":1.5}");
    }

    // 用例 5.9：weekly 缺 weekdays 或为空数组 → 47004（空数组等价「每周都不提醒」，属误操作）
    @Test
    void create_with_weekly_missing_or_empty_weekdays_throws_47004() {
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"weekly\"}");
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"weekly\",\"weekdays\":[]}");
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"weekly\",\"weekdays\":null}");
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"weekly\",\"weekdays\":\"6\"}");
    }

    // 用例 5.10：weekly 的 weekdays 含非法元素（0 / 8 / 非整数）→ 47004，ISO 星期合法区间 1..7
    @Test
    void create_with_weekly_invalid_weekday_throws_47004() {
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"weekly\",\"weekdays\":[0]}");
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"weekly\",\"weekdays\":[8]}");
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"weekly\",\"weekdays\":[1,8]}");
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"weekly\",\"weekdays\":[\"6\"]}");
        assertCreateRejectedByRecurrenceWhitelist("{\"freq\":\"weekly\",\"weekdays\":[1.5]}");
    }

    // 用例 5.11（反向保护）：合法取值边界必须放行，防止白名单校验过严
    @Test
    void create_accepts_boundary_weekday_and_day_values() {
        stubSaveReturnsArgument();
        service.create(createRequest("交房租", "10:00", null, "{\"freq\":\"monthly\",\"day\":31}"));
        service.create(createRequest("周末活动", "10:00", null, "{\"freq\":\"weekly\",\"weekdays\":[7]}"));
        service.create(createRequest("月初缴费", "10:00", null, "{\"freq\":\"monthly\",\"day\":1}"));

        ArgumentCaptor<CalendarEvent> captor = ArgumentCaptor.forClass(CalendarEvent.class);
        verify(repository, times(3)).save(captor.capture());
        List<JsonNode> saved = captor.getAllValues().stream().map(CalendarEvent::getRecurrence).toList();
        assertThat(saved.get(0).path("day").asInt()).isEqualTo(31);
        assertThat(saved.get(1).path("weekdays").get(0).asInt()).isEqualTo(7);
        assertThat(saved.get(2).path("day").asInt()).isEqualTo(1);
    }

    // ==================== update ====================

    // 用例 6：update 成功 —— 仅更新非空字段（PATCH 语义），其余保持原值
    @Test
    void update_changes_only_provided_fields() {
        CalendarEvent existing = event(5L, LocalDate.of(2026, 9, 12), null);
        existing.setDescription("原描述");
        existing.setCategoryId(3L);
        when(repository.findByIdAndFamilyIdAndDeletedAtIsNull(5L, FAMILY_ID))
                .thenReturn(Optional.of(existing));
        stubSaveReturnsArgument();

        UpdateCalendarEventRequest req = new UpdateCalendarEventRequest();
        req.setTitle("改期体检");
        req.setEventTime("08:30");

        CalendarEventResponse resp = service.update(5L, req);

        ArgumentCaptor<CalendarEvent> captor = ArgumentCaptor.forClass(CalendarEvent.class);
        verify(repository).save(captor.capture());
        CalendarEvent saved = captor.getValue();
        assertThat(saved.getTitle()).isEqualTo("改期体检");
        assertThat(saved.getEventTime()).isEqualTo("08:30");
        // 未提供的字段保持原值
        assertThat(saved.getEventDate()).isEqualTo(LocalDate.of(2026, 9, 12));
        assertThat(saved.getDescription()).isEqualTo("原描述");
        assertThat(saved.getCategoryId()).isEqualTo(3L);
        assertThat(saved.getRecurrence()).isNull();

        assertThat(resp.title()).isEqualTo("改期体检");
        assertThat(resp.eventTime()).isEqualTo("08:30");
    }

    // 用例 6.0：update 的 recurrence 补丁同样过白名单（PATCH 路径不得绕过校验）
    @Test
    void update_with_out_of_range_monthly_day_throws_47004() {
        CalendarEvent existing = event(11L, LocalDate.of(2026, 9, 12), null);
        when(repository.findByIdAndFamilyIdAndDeletedAtIsNull(11L, FAMILY_ID))
                .thenReturn(Optional.of(existing));

        UpdateCalendarEventRequest req = new UpdateCalendarEventRequest();
        req.setRecurrence("{\"freq\":\"monthly\",\"day\":32}");

        assertThatThrownBy(() -> service.update(11L, req))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> {
                    ResultException re = (ResultException) ex;
                    assertThat(re.getCode()).isEqualTo(47004);
                    assertThat(re.getMessage()).isEqualTo("每月重复请选择 1 到 31 之间的几号");
                });
        verify(repository, never()).save(any());
    }

    // 用例 6.1（缺陷回归）：recurrence 传空白串不得清空一次性日期
    // 修复前：空白串进入「切换重复」分支 → recurrence=null + eventDate=null → 撞库约束 chk_ce_date_recurrence 变 500
    @Test
    void update_with_blank_recurrence_keeps_existing_one_time_date() {
        CalendarEvent existing = event(8L, TODAY.plusDays(2), null);
        when(repository.findByIdAndFamilyIdAndDeletedAtIsNull(8L, FAMILY_ID))
                .thenReturn(Optional.of(existing));
        stubSaveReturnsArgument();

        UpdateCalendarEventRequest req = new UpdateCalendarEventRequest();
        req.setRecurrence("   ");

        CalendarEventResponse resp = service.update(8L, req);

        ArgumentCaptor<CalendarEvent> captor = ArgumentCaptor.forClass(CalendarEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEventDate()).isEqualTo(TODAY.plusDays(2));
        assertThat(captor.getValue().getRecurrence()).isNull();
        assertThat(resp.eventDate()).isEqualTo(TODAY.plusDays(2));
    }

    // 用例 6.2：一次性 → 重复（传 recurrence 即清空 eventDate，满足库层二选一约束）
    @Test
    void update_switches_one_time_event_to_recurring() {
        CalendarEvent existing = event(9L, TODAY, null);
        when(repository.findByIdAndFamilyIdAndDeletedAtIsNull(9L, FAMILY_ID))
                .thenReturn(Optional.of(existing));
        stubSaveReturnsArgument();

        UpdateCalendarEventRequest req = new UpdateCalendarEventRequest();
        req.setRecurrence("{\"freq\":\"daily\"}");

        CalendarEventResponse resp = service.update(9L, req);

        ArgumentCaptor<CalendarEvent> captor = ArgumentCaptor.forClass(CalendarEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEventDate()).isNull();
        assertThat(captor.getValue().getRecurrence().path("freq").asText()).isEqualTo("daily");
        assertThat(resp.recurrence()).contains("daily");
    }

    // 用例 6.3：重复 → 一次性（传 eventDate 即清空 recurrence）
    @Test
    void update_switches_recurring_event_to_one_time() {
        CalendarEvent existing = event(10L, null, "{\"freq\":\"monthly\",\"day\":1}");
        when(repository.findByIdAndFamilyIdAndDeletedAtIsNull(10L, FAMILY_ID))
                .thenReturn(Optional.of(existing));
        stubSaveReturnsArgument();

        UpdateCalendarEventRequest req = new UpdateCalendarEventRequest();
        req.setEventDate(TODAY.plusDays(3));

        service.update(10L, req);

        ArgumentCaptor<CalendarEvent> captor = ArgumentCaptor.forClass(CalendarEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEventDate()).isEqualTo(TODAY.plusDays(3));
        assertThat(captor.getValue().getRecurrence()).isNull();
    }

    // 用例 6.4：update 同时传日期与规则 → 47003，不落库
    @Test
    void update_with_both_date_and_recurrence_throws_47003() {
        when(repository.findByIdAndFamilyIdAndDeletedAtIsNull(11L, FAMILY_ID))
                .thenReturn(Optional.of(event(11L, TODAY, null)));

        UpdateCalendarEventRequest req = new UpdateCalendarEventRequest();
        req.setEventDate(TODAY.plusDays(1));
        req.setRecurrence("{\"freq\":\"daily\"}");

        assertThatThrownBy(() -> service.update(11L, req))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> {
                    ResultException re = (ResultException) ex;
                    assertThat(re.getCode()).isEqualTo(47003);
                    assertThat(re.getMessage()).isEqualTo("一次性日期与重复规则不能同时设置");
                });
        verify(repository, never()).save(any());
    }

    // 用例 6.5：update 传空白标题 → 47001，不落库
    @Test
    void update_with_blank_title_throws_47001() {
        when(repository.findByIdAndFamilyIdAndDeletedAtIsNull(12L, FAMILY_ID))
                .thenReturn(Optional.of(event(12L, TODAY, null)));

        UpdateCalendarEventRequest req = new UpdateCalendarEventRequest();
        req.setTitle("   ");

        assertThatThrownBy(() -> service.update(12L, req))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> assertThat(((ResultException) ex).getCode()).isEqualTo(47001));
        verify(repository, never()).save(any());
    }

    // 用例 6.6：update 传非法 recurrence → 47004，不落库
    @Test
    void update_with_malformed_recurrence_throws_47004() {
        when(repository.findByIdAndFamilyIdAndDeletedAtIsNull(13L, FAMILY_ID))
                .thenReturn(Optional.of(event(13L, TODAY, null)));

        UpdateCalendarEventRequest req = new UpdateCalendarEventRequest();
        req.setRecurrence("daily");

        assertThatThrownBy(() -> service.update(13L, req))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> assertThat(((ResultException) ex).getCode()).isEqualTo(47004));
        verify(repository, never()).save(any());
    }

    // 用例 6.7：update 不存在的日程 → 47005
    @Test
    void update_missing_event_throws_47005() {
        when(repository.findByIdAndFamilyIdAndDeletedAtIsNull(404L, FAMILY_ID))
                .thenReturn(Optional.empty());

        UpdateCalendarEventRequest req = new UpdateCalendarEventRequest();
        req.setTitle("改名");

        assertThatThrownBy(() -> service.update(404L, req))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> {
                    ResultException re = (ResultException) ex;
                    assertThat(re.getCode()).isEqualTo(47005);
                    assertThat(re.getMessage()).isEqualTo("日程不存在");
                });
    }

    // ==================== delete ====================

    // 用例 7：delete 软删 —— 设置 deletedAt 后保存，不做物理删除
    @Test
    void delete_soft_deletes_event() {
        CalendarEvent existing = event(6L, LocalDate.of(2026, 9, 12), null);
        when(repository.findByIdAndFamilyIdAndDeletedAtIsNull(6L, FAMILY_ID))
                .thenReturn(Optional.of(existing));
        stubSaveReturnsArgument();

        service.delete(6L);

        ArgumentCaptor<CalendarEvent> captor = ArgumentCaptor.forClass(CalendarEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getDeletedAt()).isNotNull();
        verify(repository, never()).delete(any());
        verify(repository, never()).deleteById(any());
    }

    // 用例 7.1：delete 不存在的日程 → 47005，不落库
    @Test
    void delete_missing_event_throws_47005() {
        when(repository.findByIdAndFamilyIdAndDeletedAtIsNull(405L, FAMILY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(405L))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> assertThat(((ResultException) ex).getCode()).isEqualTo(47005));
        verify(repository, never()).save(any());
    }

    // ==================== list / get ====================

    // 用例 8.1：includeInactive=true 走「全部未删除」仓储方法；false 走「仅启用」方法（家庭隔离取 UserContext）
    @Test
    void list_switches_repository_method_by_include_inactive_flag() {
        when(repository.findByFamilyIdAndDeletedAtIsNull(FAMILY_ID))
                .thenReturn(List.of(event(1L, TODAY, null), event(2L, null, "{\"freq\":\"daily\"}")));
        when(repository.findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(FAMILY_ID))
                .thenReturn(List.of(event(1L, TODAY, null)));

        assertThat(service.list(true)).extracting(CalendarEventResponse::id).containsExactly(1L, 2L);
        assertThat(service.list(false)).extracting(CalendarEventResponse::id).containsExactly(1L);

        verify(repository).findByFamilyIdAndDeletedAtIsNull(FAMILY_ID);
        verify(repository).findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(FAMILY_ID);
    }

    // 用例 8.2：get 命中本家庭日程 → 返回完整响应（recurrence 以 JSON 字符串回传）
    @Test
    void get_returns_owned_event() {
        CalendarEvent existing = event(3L, null, "{\"freq\":\"weekly\",\"weekdays\":[2,4]}");
        existing.setTitle("每周例会");
        when(repository.findByIdAndFamilyIdAndDeletedAtIsNull(3L, FAMILY_ID))
                .thenReturn(Optional.of(existing));

        CalendarEventResponse resp = service.get(3L);

        assertThat(resp.id()).isEqualTo(3L);
        assertThat(resp.title()).isEqualTo("每周例会");
        assertThat(resp.eventDate()).isNull();
        assertThat(resp.recurrence()).contains("weekly").contains("weekdays");
        assertThat(resp.isActive()).isTrue();
    }

    // 用例 8.3：硬约束「family_id 多租户隔离」——他人家庭的日程 id 在本家庭作用域下查不到 → 47005
    @Test
    void get_event_of_other_family_throws_47005() {
        when(repository.findByIdAndFamilyIdAndDeletedAtIsNull(99L, FAMILY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(99L))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> assertThat(((ResultException) ex).getCode()).isEqualTo(47005));
        // 必须走带 familyId 的仓储方法，不允许退化为 findById
        verify(repository).findByIdAndFamilyIdAndDeletedAtIsNull(99L, FAMILY_ID);
        verify(repository, never()).findById(any());
    }

    // ==================== todayEvents ====================

    // 用例 9：todayEvents 展开 —— 重复规则命中今日（daily/weekly 命中、weekly 未命中、
    // monthly 命中、一次性命中、一次性未命中），按 eventTime 升序；今日取固定时钟注入值
    @Test
    void today_events_expands_recurring_events_matching_today() {
        int isoDay = TODAY.getDayOfWeek().getValue();
        int otherDay = isoDay == 1 ? 2 : 1;

        CalendarEvent daily = event(1L, null, "{\"freq\":\"daily\"}");
        daily.setTitle("每日喝水");
        daily.setEventTime("08:00");

        CalendarEvent weeklyHit = event(2L, null,
                "{\"freq\":\"weekly\",\"weekdays\":[" + isoDay + "]}");
        weeklyHit.setTitle("每周例会");
        weeklyHit.setEventTime("07:30");

        CalendarEvent weeklyMiss = event(3L, null,
                "{\"freq\":\"weekly\",\"weekdays\":[" + otherDay + "]}");
        weeklyMiss.setTitle("不会命中");
        weeklyMiss.setEventTime("06:00");

        CalendarEvent monthlyHit = event(4L, null,
                "{\"freq\":\"monthly\",\"day\":" + TODAY.getDayOfMonth() + "}");
        monthlyHit.setTitle("月度账单");
        monthlyHit.setEventTime("10:00");
        monthlyHit.setAssigneeProfileId(7L);

        CalendarEvent monthlyMiss = event(8L, null,
                "{\"freq\":\"monthly\",\"day\":" + (TODAY.getDayOfMonth() == 1 ? 2 : 1) + "}");
        monthlyMiss.setTitle("下月账单");
        monthlyMiss.setEventTime("05:30");

        CalendarEvent oneTimeHit = event(5L, TODAY, null);
        oneTimeHit.setTitle("今天聚餐");
        oneTimeHit.setEventTime("18:30");

        CalendarEvent oneTimeMiss = event(6L, TODAY.plusDays(1), null);
        oneTimeMiss.setTitle("明天再说");
        oneTimeMiss.setEventTime("05:00");

        // 仓储只返回 active 日程（inactive 由 findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull 过滤）
        when(repository.findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(FAMILY_ID))
                .thenReturn(List.of(daily, weeklyHit, weeklyMiss, monthlyHit, monthlyMiss, oneTimeHit, oneTimeMiss));

        List<TodayEventItem> items = service.todayEvents();

        // 命中 4 条，按 eventTime 升序：07:30 / 08:00 / 10:00 / 18:30
        assertThat(items).extracting(TodayEventItem::title).containsExactly(
                "每周例会", "每日喝水", "月度账单", "今天聚餐");
        assertThat(items).extracting(TodayEventItem::eventTime).isSorted();
        assertThat(items).extracting(TodayEventItem::eventId)
                .doesNotContain(3L, 6L, 8L);
        TodayEventItem monthly = items.stream()
                .filter(i -> i.title().equals("月度账单")).findFirst().orElseThrow();
        assertThat(monthly.assigneeProfileId()).isEqualTo(7L);
    }

    // 用例 9.1：未知 freq 的重复日程不命中今日（前向兼容，不抛异常）
    @Test
    void today_events_ignores_unknown_recurrence_freq() {
        CalendarEvent yearly = event(20L, null, "{\"freq\":\"yearly\"}");
        yearly.setEventTime("09:00");
        when(repository.findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(FAMILY_ID))
                .thenReturn(List.of(yearly));

        assertThat(service.todayEvents()).isEmpty();
    }

    // ==================== 权限 ====================

    // 用例 10：CHILD 角色写操作（create/update/delete）→ 40302，不落库、不查库
    @Test
    void child_role_write_operations_throw_40302() {
        UserContext.setCurrent(new UserContext.CurrentUser(
                2L, "child@example.com", "孩子", FAMILY_ID, "CHILD"));

        CreateCalendarEventRequest createReq = createRequest(
                "孩子想建", "10:00", LocalDate.of(2026, 9, 15), null);
        UpdateCalendarEventRequest updateReq = new UpdateCalendarEventRequest();
        updateReq.setTitle("孩子想改");

        assertThatThrownBy(() -> service.create(createReq))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> {
                    ResultException re = (ResultException) ex;
                    assertThat(re.getCode()).isEqualTo(40302);
                    assertThat(re.getMessage()).isEqualTo("孩子成员不能创建或修改日程");
                });
        assertThatThrownBy(() -> service.update(5L, updateReq))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> assertThat(((ResultException) ex).getCode()).isEqualTo(40302));
        assertThatThrownBy(() -> service.delete(5L))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> assertThat(((ResultException) ex).getCode()).isEqualTo(40302));

        verify(repository, never()).save(any());
        verify(repository, never()).findByIdAndFamilyIdAndDeletedAtIsNull(any(), any());
    }

    // 用例 11：CHILD 角色读操作放行（孩子能看自己和家人的日程）
    @Test
    void child_role_read_operations_are_allowed() {
        UserContext.setCurrent(new UserContext.CurrentUser(
                2L, "child@example.com", "孩子", FAMILY_ID, "CHILD"));
        when(repository.findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(FAMILY_ID))
                .thenReturn(List.of(event(1L, TODAY, null)));
        when(repository.findByIdAndFamilyIdAndDeletedAtIsNull(1L, FAMILY_ID))
                .thenReturn(Optional.of(event(1L, TODAY, null)));

        assertThat(service.list(false)).hasSize(1);
        assertThat(service.get(1L).id()).isEqualTo(1L);
        assertThat(service.todayEvents()).extracting(TodayEventItem::title)
                .containsExactly("日程1");
    }

    // ==================== 时间戳 ====================

    // 用例 12：软删时间取注入时钟的当前时刻（而非系统时钟），保证可测与一致性
    @Test
    void delete_uses_injected_clock_for_deleted_at() {
        CalendarEvent existing = event(30L, TODAY, null);
        when(repository.findByIdAndFamilyIdAndDeletedAtIsNull(30L, FAMILY_ID))
                .thenReturn(Optional.of(existing));
        stubSaveReturnsArgument();

        service.delete(30L);

        ArgumentCaptor<CalendarEvent> captor = ArgumentCaptor.forClass(CalendarEvent.class);
        verify(repository).save(captor.capture());
        Instant expected = TODAY.atStartOfDay(ZONE).toInstant();
        assertThat(captor.getValue().getDeletedAt()).isEqualTo(expected);
    }

    // 用例 13：recurrence 解析结果确为 Jackson JsonNode（实体侧 JSONB 映射契约）
    @Test
    void create_stores_recurrence_as_json_node() {
        stubSaveReturnsArgument();

        service.create(createRequest("晨练", "07:00", null, "{\"freq\":\"monthly\",\"day\":15}"));

        ArgumentCaptor<CalendarEvent> captor = ArgumentCaptor.forClass(CalendarEvent.class);
        verify(repository).save(captor.capture());
        JsonNode recurrence = captor.getValue().getRecurrence();
        assertThat(recurrence.path("freq").asText()).isEqualTo("monthly");
        assertThat(recurrence.path("day").asInt()).isEqualTo(15);
    }

    // ==================== 家庭上下文守卫（审查修复 I-1） ====================

    // 用例 14：familyId 为 null（上下文缺失）时，六个入口方法一律抛 40041 且绝不触库。
    // 判别性所在：若缺守卫，各方法会拿 null familyId 去查库/落库（mock 下表现为
    // 空列表 / 47005 / 直接成功），而不是干净的 40041——「不触库 + 错误码」两个断言共同钉死守卫存在。
    @Test
    void all_entry_points_throw_40041_when_family_context_missing() {
        UserContext.setCurrent(new UserContext.CurrentUser(
                9L, "no-family@example.com", "无名", null, "OWNER"));

        CreateCalendarEventRequest createReq = createRequest(
                "无家庭新建", "10:00", TODAY, null);
        UpdateCalendarEventRequest updateReq = new UpdateCalendarEventRequest();
        updateReq.setTitle("无家庭更新");

        assertThatThrownBy(() -> service.create(createReq))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> {
                    ResultException re = (ResultException) ex;
                    assertThat(re.getCode()).isEqualTo(40041);
                    assertThat(re.getMessage()).isEqualTo("请先选择家庭，再进行日程操作");
                });
        assertThatThrownBy(() -> service.update(1L, updateReq))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> assertThat(((ResultException) ex).getCode()).isEqualTo(40041));
        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> assertThat(((ResultException) ex).getCode()).isEqualTo(40041));
        assertThatThrownBy(() -> service.list(false))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> assertThat(((ResultException) ex).getCode()).isEqualTo(40041));
        assertThatThrownBy(() -> service.get(1L))
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> assertThat(((ResultException) ex).getCode()).isEqualTo(40041));
        assertThatThrownBy(() -> service.todayEvents())
                .isInstanceOf(ResultException.class)
                .satisfies(ex -> assertThat(((ResultException) ex).getCode()).isEqualTo(40041));

        verifyNoInteractions(repository);
    }
}
