package com.familyapp.calendar.dto;

import com.familyapp.calendar.CalendarEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 日程详情响应（Change 13）。recurrence 以 JSON 字符串形式返回（与请求 DTO 对称）。
 */
public record CalendarEventResponse(
        Long id, String title, String description,
        LocalDate eventDate, String eventTime, String recurrence,
        Boolean reminderEnabled, Long assigneeProfileId, Long categoryId,
        Boolean isActive, Instant createdAt, Instant updatedAt
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 实体 → 响应转换；recurrence JsonNode 序列化为 JSON 字符串，NULL 保持 NULL */
    public static CalendarEventResponse fromEntity(CalendarEvent event) {
        String recurrenceJson = null;
        // 无需再兼容 NullNode：NullSafeJsonNodeConverter 已保证 SQL NULL ↔ Java null
        //（审查修复 P1/I-2 删除 Task 4 的读侧止痛补丁）
        if (event.getRecurrence() != null) {
            try {
                recurrenceJson = MAPPER.writeValueAsString(event.getRecurrence());
            } catch (Exception e) {
                // JsonNode 序列化不会失败；兜底保持 NULL 不阻断读取
                recurrenceJson = null;
            }
        }
        return new CalendarEventResponse(
                event.getId(), event.getTitle(), event.getDescription(),
                event.getEventDate(), event.getEventTime(), recurrenceJson,
                event.getReminderEnabled(), event.getAssigneeProfileId(), event.getCategoryId(),
                event.getIsActive(), event.getCreatedAt(), event.getUpdatedAt());
    }
}
