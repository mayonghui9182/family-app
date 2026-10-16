package com.familyapp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 一次性定时任务实体（V6 task_schedules 表）。
 * C-16 以条件 UPDATE 原子消化（design.md D1）：is_executed=false → true 只允许成功一次。
 */
@Entity
@Table(name = "task_schedules")
public class TaskSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    /** 触发日期（调度器每日 3:00 扫描当天到期行）。 */
    @Column(name = "trigger_date", nullable = false)
    private LocalDate triggerDate;

    /** 到期后要生成的任务定义 JSONB（title/priority/category_id 等）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_template", nullable = false)
    private JsonNode targetTemplate;

    /** 是否已被消化（原子抢占标志位）。 */
    @Column(name = "is_executed", nullable = false)
    private Boolean isExecuted = Boolean.FALSE;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = Instant.now();
        if (this.isExecuted == null) this.isExecuted = Boolean.FALSE;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long familyId) { this.familyId = familyId; }
    public LocalDate getTriggerDate() { return triggerDate; }
    public void setTriggerDate(LocalDate triggerDate) { this.triggerDate = triggerDate; }
    public JsonNode getTargetTemplate() { return targetTemplate; }
    public void setTargetTemplate(JsonNode targetTemplate) { this.targetTemplate = targetTemplate; }
    public Boolean getIsExecuted() { return isExecuted; }
    public void setIsExecuted(Boolean isExecuted) { this.isExecuted = isExecuted; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
