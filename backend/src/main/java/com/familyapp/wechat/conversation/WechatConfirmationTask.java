package com.familyapp.wechat.conversation;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** 已确认候选项与最终任务的可靠关联。 */
@Entity
@Table(name = "wechat_confirmation_tasks", uniqueConstraints = {
        @UniqueConstraint(name = "uq_wct_confirmation_item",
                columnNames = {"confirmation_id", "candidate_item_index"}),
        @UniqueConstraint(name = "uq_wct_confirmation_task",
                columnNames = {"confirmation_id", "task_id"})
})
public class WechatConfirmationTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false, updatable = false)
    private Long familyId;

    @Column(name = "confirmation_id", nullable = false, updatable = false)
    private Long confirmationId;

    @Column(name = "task_id", nullable = false, updatable = false)
    private Long taskId;

    @Column(name = "candidate_item_index", nullable = false, updatable = false)
    private Integer candidateItemIndex;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "initial_task_snapshot", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> initialTaskSnapshot = new HashMap<>();

    @Column(name = "last_observed_fingerprint", length = 64)
    private String lastObservedFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (initialTaskSnapshot == null) initialTaskSnapshot = new HashMap<>();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long value) { this.familyId = value; }
    public Long getConfirmationId() { return confirmationId; }
    public void setConfirmationId(Long value) { this.confirmationId = value; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long value) { this.taskId = value; }
    public Integer getCandidateItemIndex() { return candidateItemIndex; }
    public void setCandidateItemIndex(Integer value) { this.candidateItemIndex = value; }
    public Map<String, Object> getInitialTaskSnapshot() { return initialTaskSnapshot; }
    public void setInitialTaskSnapshot(Map<String, Object> value) { this.initialTaskSnapshot = value; }
    public String getLastObservedFingerprint() { return lastObservedFingerprint; }
    public void setLastObservedFingerprint(String value) { this.lastObservedFingerprint = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

