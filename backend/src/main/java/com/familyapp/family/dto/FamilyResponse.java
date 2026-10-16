package com.familyapp.family.dto;

import java.util.List;

/**
 * 家庭详情响应。包含活跃成员列表。
 */
public record FamilyResponse(
    Long id,
    String name,
    String description,
    String avatar,
    List<MemberResponse> members
) {}