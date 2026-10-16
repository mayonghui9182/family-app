package com.familyapp.calendar.dto;

/**
 * 今日日程条目（Change 13）：todayEvents 展开结果，按 eventTime 升序返回。
 */
public record TodayEventItem(Long eventId, String title, String eventTime, Long assigneeProfileId) {}
