package com.familyapp.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.familyapp.common.NullSafeJsonNodeConverter;
import jakarta.persistence.*;
import org.hibernate.annotations.Where;

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
     *
     * <p>映射口径（审查修复 P1/I-2）：经 NullSafeJsonNodeConverter 走字符串绑定，
     * Java null 落为真正的 SQL NULL。不得改回 {@code @JdbcTypeCode(SqlTypes.JSON)}——
     * 该映射会把 Java null 写成 JSON 'null' 字面量，撞 chk_ce_date_recurrence 违例
     * （V11MigrationTest 用例5/6 钉死此契约）。
     */
    @Convert(converter = NullSafeJsonNodeConverter.class)
    @Column(name = "recurrence", columnDefinition = "jsonb")
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
