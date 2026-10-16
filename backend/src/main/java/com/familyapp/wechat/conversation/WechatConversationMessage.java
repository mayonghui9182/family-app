package com.familyapp.wechat.conversation;

import jakarta.persistence.*;

import java.time.Instant;

/** 不截断的微信会话消息；正文和来源字段插入后不可修改。 */
@Entity
@Table(name = "wechat_conversation_messages", uniqueConstraints = {
        @UniqueConstraint(name = "uq_wcm_id_family", columnNames = {"id", "family_id"}),
        @UniqueConstraint(name = "uq_wcm_account_external",
                columnNames = {"wechat_account_id", "external_msg_id"})
}, indexes = @Index(
        name = "idx_wcm_account_openid_created",
        columnList = "wechat_account_id, openid, created_at"))
public class WechatConversationMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wechat_account_id", nullable = false, length = 100, updatable = false)
    private String wechatAccountId;

    @Column(name = "external_msg_id", length = 100, updatable = false)
    private String externalMsgId;

    @Column(nullable = false, length = 64, updatable = false)
    private String openid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12, updatable = false)
    private MessageDirection direction;

    @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
    private String content;

    @Column(name = "wechat_bind_id")
    private Long wechatBindId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "family_id")
    private Long familyId;

    @Column(name = "conversation_id")
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 16)
    private MessageProcessingStatus processingStatus = MessageProcessingStatus.PENDING;

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (receivedAt == null) receivedAt = now;
        if (availableAt == null) availableAt = now;
        if (processingStatus == null) processingStatus = MessageProcessingStatus.PENDING;
        if (attempts == null) attempts = 0;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getWechatAccountId() { return wechatAccountId; }
    public void setWechatAccountId(String value) { this.wechatAccountId = value; }
    public String getExternalMsgId() { return externalMsgId; }
    public void setExternalMsgId(String value) { this.externalMsgId = value; }
    public String getOpenid() { return openid; }
    public void setOpenid(String value) { this.openid = value; }
    public MessageDirection getDirection() { return direction; }
    public void setDirection(MessageDirection direction) { this.direction = direction; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Long getWechatBindId() { return wechatBindId; }
    public void setWechatBindId(Long value) { this.wechatBindId = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { this.userId = value; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long value) { this.familyId = value; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long value) { this.conversationId = value; }
    public MessageProcessingStatus getProcessingStatus() { return processingStatus; }
    public void setProcessingStatus(MessageProcessingStatus value) { this.processingStatus = value; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
    public Instant getAvailableAt() { return availableAt; }
    public void setAvailableAt(Instant value) { this.availableAt = value; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String value) { this.leaseOwner = value; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant value) { this.leaseUntil = value; }
    public String getLastError() { return lastError; }
    public void setLastError(String value) { this.lastError = value; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant value) { this.receivedAt = value; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant value) { this.processedAt = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

