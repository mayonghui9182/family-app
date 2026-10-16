package com.familyapp.wechat.conversation;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** 微信绑定级路由上下文；当前家庭只是偏好，不代表授权。 */
@Entity
@Table(name = "wechat_chat_context",
        uniqueConstraints = @UniqueConstraint(name = "uq_wcc_wechat_bind", columnNames = "wechat_bind_id"))
public class WechatChatContext {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wechat_bind_id", nullable = false)
    private Long wechatBindId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "current_family_id")
    private Long currentFamilyId;

    @Column(name = "default_family_id")
    private Long defaultFamilyId;

    public Long getDefaultFamilyId() { return defaultFamilyId; }
    public void setDefaultFamilyId(Long value) { defaultFamilyId = value; }

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_family_selection", columnDefinition = "jsonb")
    private Map<String, Object> pendingFamilySelection;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_history_delete", columnDefinition = "jsonb")
    private Map<String, Object> pendingHistoryDelete;

    @Column(name = "route_revision", nullable = false)
    private Long routeRevision = 0L;

    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (routeRevision == null) routeRevision = 0L;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getWechatBindId() { return wechatBindId; }
    public void setWechatBindId(Long wechatBindId) { this.wechatBindId = wechatBindId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getCurrentFamilyId() { return currentFamilyId; }
    public void setCurrentFamilyId(Long currentFamilyId) { this.currentFamilyId = currentFamilyId; }
    public Map<String, Object> getPendingFamilySelection() { return pendingFamilySelection; }
    public void setPendingFamilySelection(Map<String, Object> value) { this.pendingFamilySelection = value; }
    public Map<String, Object> getPendingHistoryDelete() { return pendingHistoryDelete; }
    public void setPendingHistoryDelete(Map<String, Object> value) { this.pendingHistoryDelete = value; }
    public Long getRouteRevision() { return routeRevision; }
    public void setRouteRevision(Long routeRevision) { this.routeRevision = routeRevision; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant leaseUntil) { this.leaseUntil = leaseUntil; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

