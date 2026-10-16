package com.familyapp.family.dto;

import com.familyapp.family.InviteStatus;
import java.time.Instant;

/**
 * 邀请码响应。
 */
public record InviteResponse(
    Long id,
    String code,
    Integer maxUses,
    Integer usedCount,
    Instant expiresAt,
    Instant createdAt,
    InviteStatus status
) {}