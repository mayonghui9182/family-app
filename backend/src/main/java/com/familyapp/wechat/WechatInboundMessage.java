package com.familyapp.wechat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 微信 1v1 入站文本实体（wechat_inbound_message 表映射，Change 14）。
 *
 * <p>一表两用：{@code msg_id} UNIQUE 吸收微信侧超时重推（幂等表），
 * {@code status} + {@code attempts} 驱动 {@code @Scheduled} 轮询（队列表）。
 * 受理端点只落行、不做重活；建草稿与调拆解全在异步作业内完成。
 *
 * <p>openid 仅在服务端流转，永不返回前端（沿用 Change 11 边界）。
 * family_id / user_id 为受理时裁决出的归属，二者为空即代表该行未被受理。
 */
@Entity
@Table(
    name = "wechat_inbound_message",
    uniqueConstraints = @UniqueConstraint(name = "uq_wim_msg_id", columnNames = "msg_id")
)
public class WechatInboundMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "msg_id", nullable = false, length = 64)
    private String msgId;

    @Column(nullable = false, length = 64)
    private String openid;

    /** 用户原文，受理侧超 200 字符截断。 */
    @Column(nullable = false, length = 200)
    private String content;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WechatInboundStatus status = WechatInboundStatus.PENDING;

    @Column(nullable = false)
    private Integer attempts = 0;

    /** 拆解草稿指针（agent_decompose_sessions.id）。 */
    @Column(name = "session_id")
    private Long sessionId;

    /** 闸门拒绝原因（snake_case 契约值，见 WechatInboundInternalController）。 */
    @Column(name = "skip_reason", length = 32)
    private String skipReason;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "family_id")
    private Long familyId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.receivedAt == null) {
            this.receivedAt = now;
        }
        if (this.status == null) {
            this.status = WechatInboundStatus.PENDING;
        }
        if (this.attempts == null) {
            this.attempts = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getMsgId() {
        return msgId;
    }

    public void setMsgId(String msgId) {
        this.msgId = msgId;
    }

    public String getOpenid() {
        return openid;
    }

    public void setOpenid(String openid) {
        this.openid = openid;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public WechatInboundStatus getStatus() {
        return status;
    }

    public void setStatus(WechatInboundStatus status) {
        this.status = status;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Long getFamilyId() {
        return familyId;
    }

    public void setFamilyId(Long familyId) {
        this.familyId = familyId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
