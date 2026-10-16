package com.familyapp.agent;

import com.familyapp.task.TaskStatus;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * 子待办实体（V6 迁移 subtasks 表，Change 1 建）。
 *
 * <p>Change 2 Task 2 补齐 JPA 映射：内部 Agent API（C-4 批量创建 / C-6 更新 / C-7 删除）
 * 与 C-12 few-shot 都需要读写子待办。状态沿用任务 5 值状态机枚举 {@link TaskStatus}。</p>
 */
@Entity
@Table(name = "subtasks")
public class Subtask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属家庭（多租户隔离，NOT NULL）。 */
    @Column(name = "family_id", nullable = false)
    private Long familyId;

    /** 父任务 ID（逻辑外键 tasks.id）。 */
    @Column(name = "parent_task_id", nullable = false)
    private Long parentTaskId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** 排序号，越小越靠前。 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    /** 子待办自身状态，5 值状态机。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status = TaskStatus.PENDING;

    /** 是否 AI 生成（C-4 内部批量接口强制 true）。 */
    @Column(name = "is_ai_generated", nullable = false)
    private Boolean isAiGenerated = Boolean.FALSE;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.sortOrder == null) this.sortOrder = 0;
        if (this.status == null) this.status = TaskStatus.PENDING;
        if (this.isAiGenerated == null) this.isAiGenerated = Boolean.FALSE;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long familyId) { this.familyId = familyId; }
    public Long getParentTaskId() { return parentTaskId; }
    public void setParentTaskId(Long parentTaskId) { this.parentTaskId = parentTaskId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public Boolean getIsAiGenerated() { return isAiGenerated; }
    public void setIsAiGenerated(Boolean isAiGenerated) { this.isAiGenerated = isAiGenerated; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
