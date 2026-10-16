package com.familyapp.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C-15 四种 conditions 匹配边界测试（plan Task 2 Step 1 的 12 条 + 未知类型兜底）。
 * 纯单元测试：mock 仓库与天气客户端，Clock 固定保证确定性。
 */
class RoutineMatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final RuleTemplateRepository repository = mock(RuleTemplateRepository.class);
    private final WeatherClient weatherClient = mock(WeatherClient.class);

    /** 在指定日期上构造服务（weekly/monthly 边界用例各自固定日期）。 */
    private RoutineMatcherService serviceAt(LocalDate date) {
        Clock clock = Clock.fixed(date.atStartOfDay(ZONE).toInstant(), ZONE);
        return new RoutineMatcherService(repository, weatherClient, clock);
    }

    private RuleTemplate template(String conditionsJson, String contentJson) {
        try {
            RuleTemplate t = new RuleTemplate();
            t.setFamilyId(1L);
            t.setType("routine");
            t.setName("测试模板");
            t.setConditions(MAPPER.readTree(conditionsJson));
            t.setContent(MAPPER.readTree(contentJson));
            return t;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<DueTodayRoutine> run(LocalDate today, RuleTemplate tpl) {
        when(repository.findByFamilyIdAndTypeAndIsActiveTrue(1L, "routine")).thenReturn(List.of(tpl));
        return serviceAt(today).dueToday(1L);
    }

    private static final String CONTENT = "{\"title\":\"检查书包\",\"priority\":\"HIGH\",\"needs_ai\":false}";

    // ---- weekly ----

    @Test
    void weekly_周一在列表中命中() {
        var hits = run(LocalDate.of(2026, 8, 24),
                template("{\"kind\":\"weekly\",\"weekdays\":[1,5]}", CONTENT));
        assertEquals(1, hits.size());
        assertEquals("检查书包", hits.get(0).routineTitle());
    }

    @Test
    void weekly_周五命中() {
        var hits = run(LocalDate.of(2026, 8, 28),
                template("{\"kind\":\"weekly\",\"weekdays\":[5]}", CONTENT));
        assertEquals(1, hits.size());
    }

    @Test
    void weekly_周六不命中() {
        assertTrue(run(LocalDate.of(2026, 8, 22),
                template("{\"kind\":\"weekly\",\"weekdays\":[1]}", CONTENT)).isEmpty());
    }

    // ---- monthly ----

    @Test
    void monthly_当日恰好命中() {
        assertTrue(run(LocalDate.of(2026, 9, 15),
                template("{\"kind\":\"monthly\",\"day\":15}", CONTENT)).size() == 1);
    }

    @Test
    void monthly_日期未到不命中() {
        assertTrue(run(LocalDate.of(2026, 9, 14),
                template("{\"kind\":\"monthly\",\"day\":15}", CONTENT)).isEmpty());
    }

    @Test
    void monthly_日期已过不命中() {
        assertTrue(run(LocalDate.of(2026, 9, 16),
                template("{\"kind\":\"monthly\",\"day\":15}", CONTENT)).isEmpty());
    }

    // ---- weather ----

    @Test
    void weather_类型一致命中() throws Exception {
        when(weatherClient.fetchWeatherType(anyString(), any())).thenReturn("sunny");
        var hits = run(LocalDate.of(2026, 8, 25),
                template("{\"kind\":\"weather\",\"weather_type\":\"sunny\",\"city\":\"北京\"}", CONTENT));
        assertEquals(1, hits.size());
    }

    @Test
    void weather_类型不一致不命中() throws Exception {
        when(weatherClient.fetchWeatherType(anyString(), any())).thenReturn("rain");
        assertTrue(run(LocalDate.of(2026, 8, 25),
                template("{\"kind\":\"weather\",\"weather_type\":\"sunny\",\"city\":\"北京\"}", CONTENT)).isEmpty());
    }

    @Test
    void weather_服务异常跳过该模板且不抛出() {
        when(weatherClient.fetchWeatherType(anyString(), any()))
                .thenThrow(new RuntimeException("天气服务暂时不可用"));
        assertTrue(run(LocalDate.of(2026, 8, 25),
                template("{\"kind\":\"weather\",\"weather_type\":\"sunny\",\"city\":\"北京\"}", CONTENT)).isEmpty(),
                "天气异常应按 56501 语义跳过而非抛出阻断");
    }

    // ---- combo ----

    @Test
    void combo_AND全命中才命中() throws Exception {
        String cond = "{\"kind\":\"combo\",\"op\":\"AND\",\"items\":["
                + "{\"kind\":\"weekly\",\"weekdays\":[2]},"
                + "{\"kind\":\"weather\",\"weather_type\":\"sunny\",\"city\":\"北京\"}]}";
        when(weatherClient.fetchWeatherType(anyString(), any())).thenReturn("sunny");
        assertEquals(1, run(LocalDate.of(2026, 8, 25), template(cond, CONTENT)).size());

        String missOne = "{\"kind\":\"combo\",\"op\":\"AND\",\"items\":["
                + "{\"kind\":\"weekly\",\"weekdays\":[1]},"
                + "{\"kind\":\"weather\",\"weather_type\":\"sunny\",\"city\":\"北京\"}]}";
        assertTrue(run(LocalDate.of(2026, 8, 25), template(missOne, CONTENT)).isEmpty(),
                "AND 中一个分支不命中即整体不命中");
    }

    @Test
    void combo_OR任一分支命中即命中() throws Exception {
        String cond = "{\"kind\":\"combo\",\"op\":\"OR\",\"items\":["
                + "{\"kind\":\"weekly\",\"weekdays\":[1]},"
                + "{\"kind\":\"weather\",\"weather_type\":\"sunny\",\"city\":\"北京\"}]}";
        when(weatherClient.fetchWeatherType(anyString(), any())).thenReturn("sunny");
        assertEquals(1, run(LocalDate.of(2026, 8, 25), template(cond, CONTENT)).size());
    }

    @Test
    void combo_三层嵌套递归() throws Exception {
        String cond = "{\"kind\":\"combo\",\"op\":\"AND\",\"items\":["
                + "{\"kind\":\"combo\",\"op\":\"OR\",\"items\":["
                + "{\"kind\":\"weekly\",\"weekdays\":[1]},"
                + "{\"kind\":\"weekly\",\"weekdays\":[2]}]},"
                + "{\"kind\":\"combo\",\"op\":\"AND\",\"items\":["
                + "{\"kind\":\"monthly\",\"day\":25}]}]}";
        // 外层 AND：[OR(周一,周二)=真（今日周二）] AND [AND(25日)=真] → 命中
        assertEquals(1, run(LocalDate.of(2026, 8, 25), template(cond, CONTENT)).size());
    }

    // ---- 兜底 ----

    @Test
    void 未知kind视为不命中() {
        assertTrue(run(LocalDate.of(2026, 8, 25),
                template("{\"kind\":\"cron\",\"expr\":\"0 0 2 * * ?\"}", CONTENT)).isEmpty());
    }
}
