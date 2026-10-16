package com.familyapp.family;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 家庭邀请码实体。对应 V4 DDL invites 表。
 * 邀请码为 6 位大写无歧义字母（ABCDEFGHJKMNPQRSTUVWXYZ，20 字母表，排除 0 O 1 I L）。
 * ACTIVE 状态下 code 全局唯一（partial unique index）。
 */
@Entity
@Table(name = "invites")
public class Invite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    /** 创建邀请的用户 id。 */
    @Column(name = "inviter_id", nullable = false)
    private Long inviterId;

    /** 6 位大写邀请码（无歧义字母表）。 */
    @Column(name = "code", length = 6, nullable = false)
    private String code;

    /** 最多可被接受次数（1-100，默认 1）。 */
    @Column(name = "max_uses", nullable = false)
    private Integer maxUses = 1;

    /** 已使用次数（<= max_uses）。 */
    @Column(name = "used_count", nullable = false)
    private Integer usedCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 过期时刻（通常 createdAt + 7 天）。 */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 被接受时刻（第一次 accept 成功时记录，可为空直到被消费完）。 */
    @Column(name = "accepted_at")
    private Instant acceptedAt;

    /** ACTIVE / USED / EXPIRED / CANCELLED。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private InviteStatus status = InviteStatus.ACTIVE;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long familyId) { this.familyId = familyId; }
    public Long getInviterId() { return inviterId; }
    public void setInviterId(Long inviterId) { this.inviterId = inviterId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Integer getMaxUses() { return maxUses; }
    public void setMaxUses(Integer maxUses) { this.maxUses = maxUses; }
    public Integer getUsedCount() { return usedCount; }
    public void setUsedCount(Integer usedCount) { this.usedCount = usedCount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(Instant acceptedAt) { this.acceptedAt = acceptedAt; }
    public InviteStatus getStatus() { return status; }
    public void setStatus(InviteStatus status) { this.status = status; }
}