package com.familyapp.agent;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 拆解会话草稿箱实体（agent_decompose_sessions 表映射）
 *
 * <p>设计依据：V9.sql §1.4 agent_decompose_sessions DDL + plan Task 1 Step 5c + Task 2 Step 2
 *
 * <p><b>草稿箱-原子确认模式（plan §B 核心决策 3）</b>：
 * D-1~D-4 只写本表（messages/extracted_profile_patch/proposed_tasks 三个 JSONB 列）；
 * D-5（确认）/D-6（放弃）/D-7（归档）终态才真正写业务表（tasks + person_profiles.profile_data）。
 * 用户任何时点放弃 → 0 写入污染业务数据。
 *
 * <p>H2 兼容：JSONB 映射用 @JdbcTypeCode(SqlTypes.JSON)。
 * PostgreSQL JSONB `::text` 与 H2 JSON text 序列化格式不同 → InternalAgentBGroupTest 用 contains() 而不是 equals()。
 */
@Entity
@Table(name = "agent_decompose_sessions")
public class DecomposeSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    @Column(name = "profile_id")
    private Long profileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "session_type", nullable = false, length = 20)
    private DecomposeSessionType sessionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private DecomposeSessionStatus status = DecomposeSessionStatus.ACTIVE;

    // ===== D-1 初始化快照（创建任务/访谈时的初始参数） =====
    @Column(name = "init_task_title", length = 200)
    private String initTaskTitle;

    @Column(name = "init_task_category_id")
    private Long initTaskCategoryId;

    @Column(name = "init_task_description", columnDefinition = "TEXT")
    private String initTaskDescription;

    @Column(name = "init_task_priority", length = 10)
    private String initTaskPriority;

    @Column(name = "init_task_due_date")
    private LocalDate initTaskDueDate;

    @Column(name = "init_task_id")
    private Long initTaskId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "init_profile_id_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> initProfileIdSnapshot = new HashMap<>();

    // ===== D-2~D-4 沟通/拆解产物 =====
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "messages", nullable = false, columnDefinition = "jsonb")
    private List<Object> messages = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extracted_profile_patch", columnDefinition = "jsonb")
    private Map<String, Object> extractedProfilePatch;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_tasks", columnDefinition = "jsonb")
    private List<Object> proposedTasks;

    @Column(name = "created_by", nullable = false)
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
        if (this.status == null) this.status = DecomposeSessionStatus.ACTIVE;
        if (this.messages == null) this.messages = new ArrayList<>();
        if (this.initProfileIdSnapshot == null) this.initProfileIdSnapshot = new HashMap<>();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ===== getter/setter =====

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long familyId) { this.familyId = familyId; }
    public Long getProfileId() { return profileId; }
    public void setProfileId(Long profileId) { this.profileId = profileId; }
    public DecomposeSessionType getSessionType() { return sessionType; }
    public void setSessionType(DecomposeSessionType sessionType) { this.sessionType = sessionType; }
    public DecomposeSessionStatus getStatus() { return status; }
    public void setStatus(DecomposeSessionStatus status) { this.status = status; }
    public String getInitTaskTitle() { return initTaskTitle; }
    public void setInitTaskTitle(String initTaskTitle) { this.initTaskTitle = initTaskTitle; }
    public Long getInitTaskCategoryId() { return initTaskCategoryId; }
    public void setInitTaskCategoryId(Long initTaskCategoryId) { this.initTaskCategoryId = initTaskCategoryId; }
    public String getInitTaskDescription() { return initTaskDescription; }
    public void setInitTaskDescription(String initTaskDescription) { this.initTaskDescription = initTaskDescription; }
    public String getInitTaskPriority() { return initTaskPriority; }
    public void setInitTaskPriority(String initTaskPriority) { this.initTaskPriority = initTaskPriority; }
    public LocalDate getInitTaskDueDate() { return initTaskDueDate; }
    public void setInitTaskDueDate(LocalDate initTaskDueDate) { this.initTaskDueDate = initTaskDueDate; }
    public Long getInitTaskId() { return initTaskId; }
    public void setInitTaskId(Long initTaskId) { this.initTaskId = initTaskId; }
    public Map<String, Object> getInitProfileIdSnapshot() { return initProfileIdSnapshot; }
    public void setInitProfileIdSnapshot(Map<String, Object> initProfileIdSnapshot) { this.initProfileIdSnapshot = initProfileIdSnapshot; }
    public List<Object> getMessages() { return messages; }
    public void setMessages(List<Object> messages) { this.messages = messages; }
    public Map<String, Object> getExtractedProfilePatch() { return extractedProfilePatch; }
    public void setExtractedProfilePatch(Map<String, Object> extractedProfilePatch) { this.extractedProfilePatch = extractedProfilePatch; }
    public List<Object> getProposedTasks() { return proposedTasks; }
    public void setProposedTasks(List<Object> proposedTasks) { this.proposedTasks = proposedTasks; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
