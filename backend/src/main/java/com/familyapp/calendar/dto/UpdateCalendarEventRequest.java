package com.familyapp.calendar.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 更新日程请求（Change 13）：PATCH 语义，所有字段可空，仅非空字段被更新。
 * <p>title 传空串视为清空标题，服务层拒绝；eventTime 传非空时校验 HH:mm 格式；
 * eventDate 与 recurrence 同时提供视为互斥冲突（47003）；recurrence 传空串/纯空白
 * 与不传等价（视为未提供，保持原有日期或重复规则不变）。</p>
 */
public class UpdateCalendarEventRequest {

    @Size(max = 200, message = "日程标题超过200字")
    private String title;

    private String description;

    /** 一次性事件日期（重复事件为 NULL） */
    private LocalDate eventDate;

    @Pattern(regexp = "^\\d{2}:\\d{2}$", message = "时间格式不正确，应为 HH:mm")
    private String eventTime;

    /** 重复规则 JSON 字符串：{"freq":"daily"} / {"freq":"weekly","weekdays":[2]} / {"freq":"monthly","day":15} */
    private String recurrence;

    private Boolean reminderEnabled;

    private Long assigneeProfileId;

    private Long categoryId;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDate getEventDate() { return eventDate; }
    public void setEventDate(LocalDate eventDate) { this.eventDate = eventDate; }
    public String getEventTime() { return eventTime; }
    public void setEventTime(String eventTime) { this.eventTime = eventTime; }
    public String getRecurrence() { return recurrence; }
    public void setRecurrence(String recurrence) { this.recurrence = recurrence; }
    public Boolean getReminderEnabled() { return reminderEnabled; }
    public void setReminderEnabled(Boolean reminderEnabled) { this.reminderEnabled = reminderEnabled; }
    public Long getAssigneeProfileId() { return assigneeProfileId; }
    public void setAssigneeProfileId(Long assigneeProfileId) { this.assigneeProfileId = assigneeProfileId; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
}
