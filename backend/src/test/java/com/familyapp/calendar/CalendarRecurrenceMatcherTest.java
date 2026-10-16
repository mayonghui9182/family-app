package com.familyapp.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CalendarRecurrenceMatcher 纯单元测试：月末回退语义（Change 13 终审 IMP-2 修复）。
 *
 * <p><b>为何单独建测</b>：既有服务层用例的 monthly 夹具写作
 * {@code day = TODAY.getDayOfMonth()}，期望值由「今天几号」反推得出，按构造必然命中，
 * 因此永远不可能暴露短月缺口（终审记录的测试同义反复问题）。本类一律使用**字面日期**
 * 断言，与运行日期无关，可稳定钉住跨月份的日历语义。
 *
 * <p><b>语义决策</b>：monthly 的 {@code day} 大于当月天数时，钳制到当月最后一天
 * （即「每月 31 号」在平年 2 月落在 2/28）。此为**刻意偏离 RFC 5545**（iCal 规定当月
 * 无此日则跳过）的产品口径：面向老年用户的服药/缴费提醒不得出现 61 天静默空档，
 * 且偏离点已在 {@link CalendarRecurrenceMatcher} 注释中钉死，便于将来导入 iCal 时识别。
 *
 * <p>覆盖：
 * <ol>
 *   <li>day=31 在平年 2/28 命中（修复前静默丢失）</li>
 *   <li>day=31 在 4/30、9/30 命中</li>
 *   <li>day=31 在大月 3/31 精确命中</li>
 *   <li>钳制不得误命中当月倒数第二天（3/30、2/27）</li>
 *   <li>day=30 在 2 月回退至 2/28</li>
 *   <li>day=29 平年回退 2/28；闰年精确命中 2/29 且不命中 2/28</li>
 *   <li>常规 day 不被月末规则污染（day=15 不命中 2/28）</li>
 *   <li>day 缺失/越界/非数字时恒不命中</li>
 *   <li>daily/weekly/一次性回归，确保本次仅改 monthly 分支</li>
 * </ol>
 */
class CalendarRecurrenceMatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CalendarRecurrenceMatcher matcher = new CalendarRecurrenceMatcher();

    /** 构造重复日程夹具：按 V11 互斥约束，重复事件的 eventDate 恒为 NULL。 */
    private CalendarEvent recurring(String recurrenceJson) {
        CalendarEvent event = new CalendarEvent();
        event.setTitle("日程");
        event.setEventDate(null);
        try {
            event.setRecurrence(recurrenceJson == null ? null : MAPPER.readTree(recurrenceJson));
        } catch (Exception e) {
            throw new IllegalArgumentException("夹具 JSON 非法：" + recurrenceJson, e);
        }
        return event;
    }

    /** monthly 语义断言入口：day 与待测日期。 */
    private boolean monthlyHits(int day, LocalDate date) {
        return matcher.matchesToday(recurring("{\"freq\":\"monthly\",\"day\":" + day + "}"), date);
    }

    // 用例 1：day=31 在平年 2 月末命中（修复前 2 月整天丢失）
    @Test
    void monthly_day31_hits_feb_28_in_common_year() {
        assertThat(monthlyHits(31, LocalDate.of(2026, 2, 28))).isTrue();
    }

    // 用例 2：day=31 在只有 30 天的月份回退到当月最后一天
    @Test
    void monthly_day31_hits_last_day_of_30_day_months() {
        assertThat(monthlyHits(31, LocalDate.of(2026, 4, 30))).isTrue();
        assertThat(monthlyHits(31, LocalDate.of(2026, 6, 30))).isTrue();
        assertThat(monthlyHits(31, LocalDate.of(2026, 9, 30))).isTrue();
        assertThat(monthlyHits(31, LocalDate.of(2026, 11, 30))).isTrue();
    }

    // 用例 3：day=31 在大月仍精确命中 31 号本身
    @Test
    void monthly_day31_hits_exact_31st_in_long_months() {
        assertThat(monthlyHits(31, LocalDate.of(2026, 3, 31))).isTrue();
        assertThat(monthlyHits(31, LocalDate.of(2026, 12, 31))).isTrue();
    }

    // 用例 4：回退只作用于当月最后一天，不得连带误命中倒数第二天
    @Test
    void clamped_monthly_does_not_hit_penultimate_day() {
        assertThat(monthlyHits(31, LocalDate.of(2026, 3, 30))).isFalse();
        assertThat(monthlyHits(31, LocalDate.of(2026, 2, 27))).isFalse();
        assertThat(monthlyHits(31, LocalDate.of(2026, 4, 29))).isFalse();
    }

    // 用例 5：day=30 在平年 2 月同样回退
    @Test
    void monthly_day30_hits_feb_28_in_common_year() {
        assertThat(monthlyHits(30, LocalDate.of(2026, 2, 28))).isTrue();
        assertThat(monthlyHits(30, LocalDate.of(2026, 4, 30))).isTrue();
        assertThat(monthlyHits(30, LocalDate.of(2026, 4, 29))).isFalse();
    }

    // 用例 6：day=29 平年回退 2/28；闰年 2 月有 29 天故精确命中，且 2/28 不得命中
    @Test
    void monthly_day29_clamps_in_common_year_but_hits_29th_in_leap_year() {
        assertThat(monthlyHits(29, LocalDate.of(2026, 2, 28))).isTrue();
        assertThat(monthlyHits(29, LocalDate.of(2028, 2, 29))).isTrue();
        assertThat(monthlyHits(29, LocalDate.of(2028, 2, 28))).isFalse();
    }

    // 用例 7：常规 day 不受月末规则影响（含「短月的最后一天」这一易被误伤的点）
    @Test
    void regular_day_is_not_polluted_by_month_end_rule() {
        assertThat(monthlyHits(15, LocalDate.of(2026, 2, 15))).isTrue();
        assertThat(monthlyHits(15, LocalDate.of(2026, 2, 28))).isFalse();
        assertThat(monthlyHits(1, LocalDate.of(2026, 2, 28))).isFalse();
    }

    // 用例 8：day 缺失 / 越界 / 非数字 → 恒不命中，不得因钳制而意外命中月末
    @Test
    void monthly_without_valid_day_never_hits() {
        assertThat(matcher.matchesToday(recurring("{\"freq\":\"monthly\"}"),
                LocalDate.of(2026, 2, 28))).isFalse();
        assertThat(monthlyHits(0, LocalDate.of(2026, 1, 31))).isFalse();
        assertThat(monthlyHits(-1, LocalDate.of(2026, 1, 31))).isFalse();
        assertThat(matcher.matchesToday(recurring("{\"freq\":\"monthly\",\"day\":99}"),
                LocalDate.of(2026, 1, 31))).isTrue();
        assertThat(matcher.matchesToday(recurring("{\"freq\":\"monthly\",\"day\":\"x\"}"),
                LocalDate.of(2026, 1, 31))).isFalse();
    }

    // 用例 9：daily 恒命中（含 2 月末），本次未触碰
    @Test
    void daily_still_hits_every_day() {
        CalendarEvent daily = recurring("{\"freq\":\"daily\"}");
        assertThat(matcher.matchesToday(daily, LocalDate.of(2026, 2, 28))).isTrue();
        assertThat(matcher.matchesToday(daily, LocalDate.of(2026, 9, 15))).isTrue();
    }

    // 用例 10：weekly 按 ISO 星期命中（2026-02-28 为周六），本次未触碰
    @Test
    void weekly_still_matches_by_iso_weekday() {
        CalendarEvent sat = recurring("{\"freq\":\"weekly\",\"weekdays\":[6]}");
        assertThat(LocalDate.of(2026, 2, 28).getDayOfWeek().getValue()).isEqualTo(6);
        assertThat(matcher.matchesToday(sat, LocalDate.of(2026, 2, 28))).isTrue();
        assertThat(matcher.matchesToday(sat, LocalDate.of(2026, 2, 27))).isFalse();
    }

    // 用例 11：一次性与空规则回归（SQL NULL 与 JSON 'null' 字面量两形态均按无规则处理）
    @Test
    void one_time_event_compares_event_date() {
        CalendarEvent once = recurring(null);
        once.setEventDate(LocalDate.of(2026, 2, 28));
        assertThat(matcher.matchesToday(once, LocalDate.of(2026, 2, 28))).isTrue();
        assertThat(matcher.matchesToday(once, LocalDate.of(2026, 3, 28))).isFalse();

        CalendarEvent nullLiteral = recurring("null");
        nullLiteral.setEventDate(LocalDate.of(2026, 2, 28));
        assertThat(matcher.matchesToday(nullLiteral, LocalDate.of(2026, 2, 28))).isTrue();
    }
}
