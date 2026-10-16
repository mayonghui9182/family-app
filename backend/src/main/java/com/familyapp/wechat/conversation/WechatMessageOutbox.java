package com.familyapp.wechat.conversation;

import jakarta.persistence.*;

import java.time.Instant;

/** 有序、可重试且与业务事务解耦的微信客服消息 outbox。 */
@Entity
@Table(name = "wechat_message_outbox", uniqueConstraints = {
        @UniqueConstraint(name = "uq_wmo_message", columnNames = "message_id"),
        @UniqueConstraint(name = "uq_wmo_conversation_dedupe",
                columnNames = {"conversation_id", "dedupe_key"}),
        @UniqueConstraint(name = "uq_wmo_conversation_sequence",
                columnNames = {"conversation_id", "sequence_no"})
})
public class WechatMessageOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false, updatable = false)
    private Long familyId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private Long conversationId;

    @Column(name = "message_id", nullable = false, updatable = false)
    private Long messageId;

    @Column(name = "dedupe_key", nullable = false, length = 200, updatable = false)
    private String dedupeKey;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private Long sequenceNo;

    @Column(name = "not_before", nullable = false)
    private Instant notBefore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "wechat_error_code", length = 32)
    private String wechatErrorCode;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (notBefore == null) notBefore = now;
        if (status == null) status = OutboxStatus.PENDING;
        if (attempts == null) attempts = 0;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long value) { this.familyId = value; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long value) { this.conversationId = value; }
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long value) { this.messageId = value; }
    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String value) { this.dedupeKey = value; }
    public Long getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(Long value) { this.sequenceNo = value; }
    public Instant getNotBefore() { return notBefore; }
    public void setNotBefore(Instant value) { this.notBefore = value; }
    public OutboxStatus getStatus() { return status; }
    public void setStatus(OutboxStatus value) { this.status = value; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer value) { this.attempts = value; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant value) { this.nextRetryAt = value; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String value) { this.leaseOwner = value; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant value) { this.leaseUntil = value; }
    public String getWechatErrorCode() { return wechatErrorCode; }
    public void setWechatErrorCode(String value) { this.wechatErrorCode = value; }
    public String getLastError() { return lastError; }
    public void setLastError(String value) { this.lastError = value; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant value) { this.sentAt = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

