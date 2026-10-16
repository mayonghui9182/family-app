package com.familyapp.profile.dto;

import com.familyapp.profile.PersonProfile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 人员档案响应 DTO（P-1/P-2/P-3/P-4 端点返回）
 *
 * 设计依据：plan Step 5 + PersonProfileControllerTest jsonPath 验证字段
 * - 用 record（不可变，匹配 PersonProfileControllerTest response.id() accessor）
 * - profileType 用 String（非 enum），方便 JSON 序列化 + jsonPath 验证 $.data.profileType
 * - profileData 透传 Map<String, Object>（JSONB 原样）
 * - from(PersonProfile) 静态工厂方法，方便 Service 层构造
 */
public record PersonProfileResponse(
        Long id,
        String profileType,
        String name,
        String avatarEmoji,
        String gender,
        LocalDate birthday,
        String relation,
        List<String> tags,
        Map<String, Object> profileData
) {
    /** 从 PersonProfile 实体构造响应 DTO */
    public static PersonProfileResponse from(PersonProfile profile) {
        return new PersonProfileResponse(
                profile.getId(),
                profile.getProfileType() != null ? profile.getProfileType().name() : null,
                profile.getName(),
                profile.getAvatarEmoji(),
                profile.getGender(),
                profile.getBirthday(),
                profile.getRelation(),
                profile.getTags(),
                profile.getProfileData()
        );
    }
}
