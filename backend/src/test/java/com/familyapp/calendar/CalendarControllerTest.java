package com.familyapp.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyapp.calendar.dto.CreateCalendarEventRequest;
import com.familyapp.calendar.dto.UpdateCalendarEventRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CalendarController 端点测试（Change 13 Task 4，E-1~E-6）。
 *
 * <p>口径说明：
 * <ul>
 *   <li>test profile 下 {@code familyapp.auth.enabled=false} → 不注册拦截器，
 *       用户上下文由 {@code TestUserContextFilter} 注入（familyId=1，role 可用
 *       {@code X-Test-Role} 头覆盖）</li>
 *   <li>时钟经 {@code @Primary Clock} 固定为 2026-09-15（Asia/Shanghai），
 *       今日展开断言不随系统日期漂移</li>
 *   <li>标题用自增序号保证唯一，避免同一 Spring 上下文内 H2 数据跨用例累积互相干扰</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CalendarControllerTest {

    /** 固定「今日」：与 CalendarEventServiceTest 同口径，避免时间依赖型断言 */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CalendarEventRepository repository;
    @Autowired JdbcTemplate jdbc;

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(TODAY.atStartOfDay(ZONE).toInstant(), ZONE);
        }
    }

    /** 唯一标题，防止跨用例数据累积干扰 */
    private static String title(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet();
    }

    /** 构造一个合法的一次性日程请求 */
    private static CreateCalendarEventRequest onceRequest(String title) {
        CreateCalendarEventRequest req = new CreateCalendarEventRequest();
        req.setTitle(title);
        req.setEventDate(TODAY.plusDays(3));
        req.setEventTime("09:30");
        return req;
    }

    private Long createOnce(String title) throws Exception {
        String body = mvc.perform(post("/api/calendar/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(onceRequest(title))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body).get("data").get("id").longValue();
    }

    // ==================== E-2 POST /api/calendar/events ====================

    @Test
    void POST_events_create_once_event_returns_201_envelope() throws Exception {
        String title = title("一次性");
        mvc.perform(post("/api/calendar/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(onceRequest(title))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.title").value(title))
                .andExpect(jsonPath("$.data.eventDate").value(TODAY.plusDays(3).toString()))
                .andExpect(jsonPath("$.data.eventTime").value("09:30"))
                .andExpect(jsonPath("$.data.recurrence").doesNotExist())
                .andExpect(jsonPath("$.data.isActive").value(true))
                .andExpect(jsonPath("$.data.reminderEnabled").value(true));
    }

    @Test
    void POST_events_create_daily_recurrence_echoes_rule() throws Exception {
        String title = title("每天");
        CreateCalendarEventRequest req = new CreateCalendarEventRequest();
        req.setTitle(title);
        req.setEventTime("07:00");
        req.setRecurrence("{\"freq\":\"daily\"}");
        mvc.perform(post("/api/calendar/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.eventDate").doesNotExist())
                .andExpect(jsonPath("$.data.recurrence", containsString("daily")));
    }

    /**
     * @Valid 生效证据一：空白标题由 DTO 的 {@code @NotBlank} 拦下（40099 + DTO 中文文案）。
     * 若 controller 漏挂 @Valid，则会落到服务层 47001，此断言即失败。
     */
    @Test
    void POST_events_blank_title_rejected_by_dto_validation() throws Exception {
        CreateCalendarEventRequest req = onceRequest("   ");
        mvc.perform(post("/api/calendar/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40099))
                .andExpect(jsonPath("$.msg").value("日程标题不能为空"));
    }

    /**
     * @Valid 生效证据二：{@code @Pattern} 的时间格式校验。
     * 服务层对同场景返回 47002，故 40099 是「@Valid 已生效」的判别性断言。
     */
    @Test
    void POST_events_bad_time_format_rejected_by_dto_validation() throws Exception {
        CreateCalendarEventRequest req = onceRequest(title("时间格式"));
        req.setEventTime("9:00");
        mvc.perform(post("/api/calendar/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40099))
                .andExpect(jsonPath("$.msg", containsString("HH:mm")));
    }

    /** @Valid 生效证据三：{@code @Size(max=200)} 标题超长 */
    @Test
    void POST_events_title_too_long_rejected_by_dto_validation() throws Exception {
        CreateCalendarEventRequest req = onceRequest("长".repeat(201));
        mvc.perform(post("/api/calendar/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40099))
                .andExpect(jsonPath("$.msg").value("日程标题超过200字"));
    }

    /** 未挂 @Valid 时该请求会被服务层 47002 拒绝；挂上后应为 40099，作为 update 侧的判别性断言 */
    @Test
    void PUT_events_bad_time_format_rejected_by_dto_validation() throws Exception {
        Long id = createOnce(title("更新校验"));
        UpdateCalendarEventRequest ur = new UpdateCalendarEventRequest();
        ur.setEventTime("08:00:00");
        mvc.perform(put("/api/calendar/events/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(ur)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40099))
                .andExpect(jsonPath("$.msg", containsString("HH:mm")));
    }

    @Test
    void PUT_events_title_too_long_rejected_by_dto_validation() throws Exception {
        Long id = createOnce(title("更新超长"));
        UpdateCalendarEventRequest ur = new UpdateCalendarEventRequest();
        ur.setTitle("长".repeat(201));
        mvc.perform(put("/api/calendar/events/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(ur)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40099))
                .andExpect(jsonPath("$.msg").value("日程标题超过200字"));
    }

    @Test
    void POST_events_date_with_recurrence_conflict_returns_47003() throws Exception {
        CreateCalendarEventRequest req = onceRequest(title("冲突"));
        req.setRecurrence("{\"freq\":\"daily\"}");
        mvc.perform(post("/api/calendar/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(47003));
    }

    @Test
    void POST_events_child_role_returns_403() throws Exception {
        CreateCalendarEventRequest req = onceRequest(title("孩子创建"));
        mvc.perform(post("/api/calendar/events")
                        .header("X-Test-Role", "CHILD")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302))
                .andExpect(jsonPath("$.msg").value("孩子成员不能创建或修改日程"));
    }

    // ==================== E-1 GET /api/calendar/events ====================

    @Test
    void GET_events_list_contains_created_items() throws Exception {
        String title = title("列表");
        createOnce(title);
        mvc.perform(get("/api/calendar/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[*].title", hasItem(title)));
    }

    /**
     * includeInactive 参数直连服务层两个查询分支。
     * 停用（isActive=false）没有对外端点，故直接用仓储播种，只验证 controller 传参口径。
     */
    @Test
    void GET_events_includeInactive_controls_inactive_items() throws Exception {
        String title = title("停用");
        CalendarEvent inactive = new CalendarEvent();
        inactive.setFamilyId(1L);
        inactive.setTitle(title);
        inactive.setEventDate(TODAY.plusDays(5));
        inactive.setEventTime("10:00");
        inactive.setIsActive(false);
        repository.save(inactive);

        mvc.perform(get("/api/calendar/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].title", not(hasItem(title))));
        mvc.perform(get("/api/calendar/events").param("includeInactive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].title", hasItem(title)));
    }

    // ==================== E-3 GET /api/calendar/events/{id} ====================

    @Test
    void GET_events_by_id_returns_detail() throws Exception {
        String title = title("详情");
        Long id = createOnce(title);
        mvc.perform(get("/api/calendar/events/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.title").value(title))
                .andExpect(jsonPath("$.data.eventDate").value(TODAY.plusDays(3).toString()))
                .andExpect(jsonPath("$.data.eventTime").value("09:30"));
    }

    /** 一次性日程从库里读回来时 recurrence 必须仍是 null（JSONB NULL 不得变成 NullNode 字符串） */
    @Test
    void GET_events_by_id_once_event_has_null_recurrence() throws Exception {
        Long id = createOnce(title("详情无重复"));
        mvc.perform(get("/api/calendar/events/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recurrence").doesNotExist());
    }

    /**
     * 库层契约：一次性日程落库后 recurrence 列必须是<b>真正的 SQL NULL</b>。
     *
     * <p>审查修复（P1/I-2）：此前 {@code @JdbcTypeCode(SqlTypes.JSON)} 会把 Java null
     * 写成 JSON 'null' 字面量（IS NULL 为 false），在生产 PG 上直接撞
     * chk_ce_date_recurrence 违例。现已改挂 null 安全的 AttributeConverter，
     * 断言随之收紧：只认 IS NULL，不再接受 'null' 字面量的双形态豁免。
     */
    @Test
    void once_event_persists_recurrence_as_sql_null() throws Exception {
        Long id = createOnce(title("库层空值"));
        Boolean isSqlNull = jdbc.queryForObject(
                "SELECT CASE WHEN recurrence IS NULL THEN TRUE ELSE FALSE END "
                        + "FROM calendar_events WHERE id = ?", Boolean.class, id);
        assertTrue(Boolean.TRUE.equals(isSqlNull),
                "一次性日程的 recurrence 必须是 SQL NULL，不允许 JSON 'null' 字面量");
    }

    @Test
    void GET_events_by_unknown_id_returns_47005() throws Exception {
        mvc.perform(get("/api/calendar/events/999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(47005))
                .andExpect(jsonPath("$.msg").value("日程不存在"));
    }

    // ==================== E-4 PUT /api/calendar/events/{id} ====================

    @Test
    void PUT_events_updates_fields_and_persists() throws Exception {
        Long id = createOnce(title("更新"));
        UpdateCalendarEventRequest ur = new UpdateCalendarEventRequest();
        ur.setTitle("更新后的标题");
        ur.setEventTime("20:15");
        ur.setDescription("改过了");
        mvc.perform(put("/api/calendar/events/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(ur)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.title").value("更新后的标题"))
                .andExpect(jsonPath("$.data.eventTime").value("20:15"))
                .andExpect(jsonPath("$.data.description").value("改过了"));

        mvc.perform(get("/api/calendar/events/" + id))
                .andExpect(jsonPath("$.data.eventTime").value("20:15"));
    }

    @Test
    void PUT_events_switch_once_to_daily_recurrence_clears_date() throws Exception {
        Long id = createOnce(title("转重复"));
        UpdateCalendarEventRequest ur = new UpdateCalendarEventRequest();
        ur.setRecurrence("{\"freq\":\"daily\"}");
        mvc.perform(put("/api/calendar/events/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(ur)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventDate").doesNotExist())
                .andExpect(jsonPath("$.data.recurrence", containsString("daily")));
    }

    @Test
    void PUT_events_child_role_returns_403() throws Exception {
        Long id = createOnce(title("孩子更新"));
        UpdateCalendarEventRequest ur = new UpdateCalendarEventRequest();
        ur.setTitle("孩子改的");
        mvc.perform(put("/api/calendar/events/" + id)
                        .header("X-Test-Role", "CHILD")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(ur)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302));
    }

    // ==================== E-5 DELETE /api/calendar/events/{id} ====================

    @Test
    void DELETE_events_soft_deletes_and_hides_from_detail() throws Exception {
        String title = title("删除");
        Long id = createOnce(title);

        mvc.perform(delete("/api/calendar/events/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));

        mvc.perform(get("/api/calendar/events"))
                .andExpect(jsonPath("$.data[*].title", not(hasItem(title))));
        mvc.perform(get("/api/calendar/events/" + id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(47005));
    }

    @Test
    void DELETE_events_child_role_returns_403() throws Exception {
        Long id = createOnce(title("孩子删除"));
        mvc.perform(delete("/api/calendar/events/" + id)
                        .header("X-Test-Role", "CHILD"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302));
    }

    // ==================== E-6 GET /api/calendar/today ====================

    @Test
    void GET_today_expands_matching_events_sorted_by_time() throws Exception {
        // 命中：每日重复 + 今日命中的每周重复 + 今日命中的每月重复 + 固定时钟下的今日一次性
        String run = title("今日跑步");
        CreateCalendarEventRequest daily = new CreateCalendarEventRequest();
        daily.setTitle(run);
        daily.setEventTime("08:00");
        daily.setRecurrence("{\"freq\":\"daily\"}");
        mvc.perform(post("/api/calendar/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(daily)))
                .andExpect(status().isCreated());

        String meeting = title("今日周会");
        CreateCalendarEventRequest weekly = new CreateCalendarEventRequest();
        weekly.setTitle(meeting);
        weekly.setEventTime("14:00");
        weekly.setRecurrence("{\"freq\":\"weekly\",\"weekdays\":[" + TODAY.getDayOfWeek().getValue() + "]}");
        mvc.perform(post("/api/calendar/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(weekly)))
                .andExpect(status().isCreated());

        String pay = title("今日缴费");
        CreateCalendarEventRequest monthly = new CreateCalendarEventRequest();
        monthly.setTitle(pay);
        monthly.setEventTime("19:00");
        monthly.setRecurrence("{\"freq\":\"monthly\",\"day\":" + TODAY.getDayOfMonth() + "}");
        mvc.perform(post("/api/calendar/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(monthly)))
                .andExpect(status().isCreated());

        String doctor = title("今日就医");
        CreateCalendarEventRequest todayOnce = new CreateCalendarEventRequest();
        todayOnce.setTitle(doctor);
        todayOnce.setEventDate(TODAY);
        todayOnce.setEventTime("10:00");
        mvc.perform(post("/api/calendar/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(todayOnce)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.eventDate").value(TODAY.toString()));

        // 不命中：非今日的一次性事件 + 不匹配星期的每周事件
        String tomorrow = title("明日事项");
        CreateCalendarEventRequest otherDay = new CreateCalendarEventRequest();
        otherDay.setTitle(tomorrow);
        otherDay.setEventDate(TODAY.plusDays(1));
        otherDay.setEventTime("11:00");
        mvc.perform(post("/api/calendar/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(otherDay)))
                .andExpect(status().isCreated());

        int nonMatchingWeekday = TODAY.getDayOfWeek().getValue() % 7 + 1;
        String otherWeekdayTitle = title("每周错开");
        CreateCalendarEventRequest otherWeekday = new CreateCalendarEventRequest();
        otherWeekday.setTitle(otherWeekdayTitle);
        otherWeekday.setEventTime("12:00");
        otherWeekday.setRecurrence("{\"freq\":\"weekly\",\"weekdays\":[" + nonMatchingWeekday + "]}");
        mvc.perform(post("/api/calendar/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(otherWeekday)))
                .andExpect(status().isCreated());

        String body = mvc.perform(get("/api/calendar/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode data = json.readTree(body).get("data");
        List<String> titles = new ArrayList<>();
        Map<String, String> timeByTitle = new HashMap<>();
        data.forEach(item -> {
            titles.add(item.get("title").asText());
            timeByTitle.put(item.get("title").asText(), item.get("eventTime").asText());
        });
        // 一次性日程按 eventDate==今日命中（证明 @Primary Clock 生效）
        assertTrue(titles.contains(doctor), "一次性日程未按今日命中 doctor=" + doctor + " titles=" + titles);
        assertTrue(titles.contains(run), "每日重复日程未命中 run=" + run + " titles=" + titles);
        assertTrue(titles.contains(meeting), "每周重复日程未命中 meeting=" + meeting + " titles=" + titles);
        assertTrue(titles.contains(pay), "每月重复日程未命中 pay=" + pay + " titles=" + titles);
        assertFalse(titles.contains(tomorrow), titles.toString());
        assertFalse(titles.contains(otherWeekdayTitle), titles.toString());
        // 只比对本用例造的 4 条，避免同上下文累积数据干扰：期望按 eventTime 升序
        assertEquals(List.of("08:00", "10:00", "14:00", "19:00"),
                List.of(run, doctor, meeting, pay).stream().map(timeByTitle::get).toList(),
                "今日日程须按 eventTime 升序");
    }

    @Test
    void GET_today_response_shape_excludes_non_daily_once_event() throws Exception {
        String title = title("后天事项");
        createOnce(title);
        mvc.perform(get("/api/calendar/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].title", not(hasItem(title))));
    }

    /** 今日条目响应形状：TodayEventItem 的 4 个字段，不含日程详情才有的字段 */
    @Test
    void GET_today_item_shape_has_four_declared_fields() throws Exception {
        String title = title("字段检查");
        CreateCalendarEventRequest daily = new CreateCalendarEventRequest();
        daily.setTitle(title);
        daily.setEventTime("06:30");
        daily.setRecurrence("{\"freq\":\"daily\"}");
        Long id = json.readTree(mvc.perform(post("/api/calendar/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(daily)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data").get("id").longValue();

        String body = mvc.perform(get("/api/calendar/today"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode item = null;
        for (JsonNode node : json.readTree(body).get("data")) {
            if (node.get("eventId").asLong() == id) {
                item = node;
                break;
            }
        }
        assertNotNull(item, "今日展开应包含刚创建的每日日程");
        List<String> fields = new ArrayList<>();
        item.fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("eventId", "title", "eventTime", "assigneeProfileId")
                        .stream().sorted().toList(),
                fields.stream().sorted().toList(), "TodayEventItem 字段集不匹配");
        assertEquals(title, item.get("title").asText());
        assertEquals("06:30", item.get("eventTime").asText());
    }
}
