package com.familyapp.family.dto;

import com.familyapp.family.FamilyRole;
import java.time.Instant;

/**
 * 登录响应中 families[] 的每一项。包含角色和中文角色名。
 */
public record FamilySummaryResponse(
    Long id,
    String name,
    FamilyRole role,
    String roleName,
    Instant joinedAt
) {}