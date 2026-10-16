package com.familyapp.family;

/**
 * 邀请码状态。严格对应 V4 DDL invites.status CHECK IN (ACTIVE,USED,EXPIRED,CANCELLED)。
 */
public enum InviteStatus {
    /** 有效，可接受。 */
    ACTIVE,
    /** 已用完 max_uses。 */
    USED,
    /** 超过 expires_at。 */
    EXPIRED,
    /** 邀请者主动取消。 */
    CANCELLED
}