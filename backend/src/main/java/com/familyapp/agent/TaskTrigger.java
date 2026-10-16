package com.familyapp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 依赖触发规则实体（V6 task_triggers 表）。
 * 源任务 COMPLETED 边沿（C-17 on-completed）读取 active 触发器，
 * 按 target_template 决定走 B-1 AI 拆解还是直建 PENDING。
 */
@Entity
@Table(name = "task_triggers")
public class TaskTrigger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    /** 触发源任务 ID。 */
    @Column(name = "source_task_id", nullable = false)
    private Long sourceTaskId;

    /** 触发类型，当前仅 on_complete。 */
    @Column(name = "trigger_type", nullable = false, length = 20)
    private String triggerType = "on_complete";

    /**
     * 下游任务定义 JSONB。键约定：
     * title/description/priority/category_id/due_date/assignee_id +
     * needs_ai_decomposition（优先）与 needs_ai（兼容别名）二选一。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_template", nullable = false)
    private JsonNode targetTemplate;

    /** 是否生效（停用后不再触发下游）。 */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = Boolean.TRUE;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = Instant.now();
        if (this.isActive == null) this.isActive = Boolean.TRUE;
        if (this.triggerType == null) this.triggerType = "on_complete";
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long familyId) { this.familyId = familyId; }
    public Long getSourceTaskId() { return sourceTaskId; }
    public void setSourceTaskId(Long sourceTaskId) { this.sourceTaskId = sourceTaskId; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public JsonNode getTargetTemplate() { return targetTemplate; }
    public void setTargetTemplate(JsonNode targetTemplate) { this.targetTemplate = targetTemplate; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
