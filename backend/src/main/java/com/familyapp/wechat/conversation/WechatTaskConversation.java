package com.familyapp.wechat.conversation;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** 一个微信绑定在一个家庭内的可恢复任务会话。 */
@Entity
@Table(name = "wechat_task_conversations", uniqueConstraints = {
        @UniqueConstraint(name = "uq_wtc_id_family", columnNames = {"id", "family_id"}),
        @UniqueConstraint(name = "uq_wtc_active_slot",
                columnNames = {"wechat_bind_id", "family_id", "active_slot"})
})
public class WechatTaskConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    @Column(name = "wechat_bind_id", nullable = false)
    private Long wechatBindId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "parent_conversation_id")
    private Long parentConversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ConversationStatus status = ConversationStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "paused_from_status", length = 24)
    private ConversationStatus pausedFromStatus;

    @Column(name = "active_slot")
    private Short activeSlot = 1;

    @Column(name = "current_revision", nullable = false)
    private Integer currentRevision = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "slot_state", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> slotState = new HashMap<>();

    @Column(name = "rolling_summary", columnDefinition = "TEXT")
    private String rollingSummary;

    @Column(name = "summary_through_message_id")
    private Long summaryThroughMessageId;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (lastActivityAt == null) lastActivityAt = now;
        if (status == null) status = ConversationStatus.ACTIVE;
        if (activeSlot == null && status.isRecoverable()) activeSlot = 1;
        if (currentRevision == null) currentRevision = 0;
        if (slotState == null) slotState = new HashMap<>();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long familyId) { this.familyId = familyId; }
    public Long getWechatBindId() { return wechatBindId; }
    public void setWechatBindId(Long wechatBindId) { this.wechatBindId = wechatBindId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getParentConversationId() { return parentConversationId; }
    public void setParentConversationId(Long value) { this.parentConversationId = value; }
    public ConversationStatus getStatus() { return status; }
    public void setStatus(ConversationStatus status) { this.status = status; }
    public ConversationStatus getPausedFromStatus() { return pausedFromStatus; }
    public void setPausedFromStatus(ConversationStatus value) { this.pausedFromStatus = value; }
    public Short getActiveSlot() { return activeSlot; }
    public void setActiveSlot(Short activeSlot) { this.activeSlot = activeSlot; }
    public Integer getCurrentRevision() { return currentRevision; }
    public void setCurrentRevision(Integer value) { this.currentRevision = value; }
    public Map<String, Object> getSlotState() { return slotState; }
    public void setSlotState(Map<String, Object> slotState) { this.slotState = slotState; }
    public String getRollingSummary() { return rollingSummary; }
    public void setRollingSummary(String value) { this.rollingSummary = value; }
    public Long getSummaryThroughMessageId() { return summaryThroughMessageId; }
    public void setSummaryThroughMessageId(Long value) { this.summaryThroughMessageId = value; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public void setLastActivityAt(Instant value) { this.lastActivityAt = value; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

