package com.familyapp.task;

import jakarta.persistence.*;
import org.hibernate.annotations.Where;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "tasks")
@Where(clause = "deleted_at IS NULL")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status = TaskStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TaskPriority priority = TaskPriority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskSource source = TaskSource.MANUAL;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "assignee_id")
    private Long assigneeId;

    /** Change 8 新列过渡策略：双字段共存 1 大版本，新 UI 优先使用 assignee_profile_id（V9.sql 已 FK 到 person_profiles）。 */
    @Column(name = "assignee_profile_id")
    private Long assigneeProfileId;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** C-31 pick：选中的模板版本 ID（AI 拆解时锁定该版本）。 */
    @Column(name = "target_template_version_id")
    private Long targetTemplateVersionId;

    /** Change 13: 日程任务的具体时间点 HH:mm（可空） */
    @Column(name = "due_time", length = 5)
    private String dueTime;

    /** Change 13: 来源日程 ID（可空，非日程任务为 NULL） */
    @Column(name = "source_event_id")
    private Long sourceEventId;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) this.status = TaskStatus.PENDING;
        if (this.priority == null) this.priority = TaskPriority.MEDIUM;
        if (this.source == null) this.source = TaskSource.MANUAL;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public TaskPriority getPriority() { return priority; }
    public void setPriority(TaskPriority priority) { this.priority = priority; }
    public TaskSource getSource() { return source; }
    public void setSource(TaskSource source) { this.source = source; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long familyId) { this.familyId = familyId; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getAssigneeId() { return assigneeId; }
    public void setAssigneeId(Long assigneeId) { this.assigneeId = assigneeId; }
    public Long getAssigneeProfileId() { return assigneeProfileId; }
    public void setAssigneeProfileId(Long assigneeProfileId) { this.assigneeProfileId = assigneeProfileId; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public Long getTargetTemplateVersionId() { return targetTemplateVersionId; }
    public void setTargetTemplateVersionId(Long targetTemplateVersionId) { this.targetTemplateVersionId = targetTemplateVersionId; }
    public String getDueTime() { return dueTime; }
    public void setDueTime(String dueTime) { this.dueTime = dueTime; }
    public Long getSourceEventId() { return sourceEventId; }
    public void setSourceEventId(Long sourceEventId) { this.sourceEventId = sourceEventId; }
}