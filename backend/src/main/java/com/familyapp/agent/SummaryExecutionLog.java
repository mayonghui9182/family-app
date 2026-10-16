package com.familyapp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 每日总结执行日志实体（V6 summary_execution_log 表）。
 * C-14 log_summary 写入；(family_id, run_at::date) 表达式唯一索引在生产 PG 由
 * afterMigrate 回调补建（H2 不支持表达式索引），起分布式锁作用。
 */
@Entity
@Table(name = "summary_execution_log")
public class SummaryExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    /** 执行时间。 */
    @Column(name = "run_at", nullable = false)
    private Instant runAt;

    /** 执行状态：running（C-19 锁占位，Change 5）| success | failed。 */
    @Column(nullable = false, length = 10)
    private String status;

    /** 失败原因说明；status=failed 且有失败分类时复用此列存 failed_categories JSON 文本。 */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** 本次总结更新的分类 ID 数组 JSONB。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "categories_updated")
    private JsonNode categoriesUpdated;

    /** 触发来源：auto（22:00 调度）| manual（A-2 手动触发）。 */
    @Column(name = "trigger_source", nullable = false, length = 10)
    private String triggerSource = "auto";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.runAt == null) this.runAt = now;
        if (this.createdAt == null) this.createdAt = now;
        if (this.triggerSource == null) this.triggerSource = "auto";
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long familyId) { this.familyId = familyId; }
    public Instant getRunAt() { return runAt; }
    public void setRunAt(Instant runAt) { this.runAt = runAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public JsonNode getCategoriesUpdated() { return categoriesUpdated; }
    public void setCategoriesUpdated(JsonNode categoriesUpdated) { this.categoriesUpdated = categoriesUpdated; }
    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }
    public Instant getCreatedAt() { return createdAt; }
}
