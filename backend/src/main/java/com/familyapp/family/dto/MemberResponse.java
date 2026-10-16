package com.familyapp.family.dto;

import com.familyapp.family.FamilyRole;
import java.time.Instant;

/**
 * 家庭详情中的成员列表项。roleName 为中文角色名。
 */
public record MemberResponse(
    Long userId,
    String nickname,
    FamilyRole role,
    String roleName,
    Instant joinedAt
) {}