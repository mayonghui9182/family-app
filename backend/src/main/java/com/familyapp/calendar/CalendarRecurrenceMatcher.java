package com.familyapp.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 日程重复规则匹配器（Change 13 Task 5）：判断日程是否命中指定日期。
 *
 * <p>由 {@code CalendarEventService} 的私有 {@code matchesToday} 提取而来，供服务今日展开与
 * 调度器生成任务共用，消除逻辑重复。无状态，可安全作为单例 Bean 注入。
 */
@Component
public class CalendarRecurrenceMatcher {

    /**
     * 判断日程是否命中给定日期：一次性事件比对 eventDate；重复事件按 freq（daily/weekly/monthly）匹配。
     */
    public boolean matchesToday(CalendarEvent event, LocalDate date) {
        JsonNode recurrence = event.getRecurrence();
        // NullSafeJsonNodeConverter 已保证 SQL NULL ↔ Java null（审查修复 P1/I-2 删除了 Task 4 的
        // 读侧止痛补丁）。isNull() 仅作为最小防御保留：万一库里存在历史 JSON 'null' 字面量行，
        // 也按「无重复规则」处理而非撞 default 分支恒 false。提取前后行为必须完全一致。
        if (recurrence == null || recurrence.isNull()) {
            return date.equals(event.getEventDate());
        }
        String freq = recurrence.path("freq").asText("");
        return switch (freq) {
            case "daily" -> true;
            case "weekly" -> weeklyHit(recurrence, date);
            case "monthly" -> monthlyHit(recurrence, date);
            default -> false;
        };
    }

    /** weekly：ISO 星期（1=周一 … 7=周日）落在 weekdays 数组即命中。 */
    private boolean weeklyHit(JsonNode recurrence, LocalDate date) {
        int isoDay = date.getDayOfWeek().getValue();
        for (JsonNode d : recurrence.path("weekdays")) {
            if (d.asInt() == isoDay) {
                return true;
            }
        }
        return false;
    }

    /**
     * monthly：day 与今日「几号」相同即命中；当月没有这一天时回退到当月最后一天（月末钳制）。
     *
     * <p>这里刻意偏离 RFC 5545。标准 RRULE 语义下「每月 31 号」在 2 月及所有 30 天的月份会
     * 静默跳过；但本产品面向家庭场景、硬约束是老人易用——用户在 UI 上选「每月 31 号」表达的
     * 意图是「每个月都提醒我一次，就在月末」，连续两个月什么都不发生等于功能失效且无法解释。
     * 故采用钳制语义：命中日期取 min(day, 当月天数)，月末事件在短月落在当月最后一天。
     * 该取舍由用户在 Change 13 全分支终审 IMP-2 处拍板（方案 A，2026-09-10），勿据 RFC
     * 直觉「修正」回跳过语义；如需变更请先更新 spec 与本注释。
     *
     * <p>day 缺失、非数字或非正数视为无效规则恒不命中（写路径另有取值白名单兜住新数据）。
     */
    private boolean monthlyHit(JsonNode recurrence, LocalDate date) {
        int day = recurrence.path("day").asInt(-1);
        if (day <= 0) {
            return false;
        }
        return Math.min(day, date.lengthOfMonth()) == date.getDayOfMonth();
    }
}
