package com.familyapp.profile.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 创建人员档案请求 DTO（P-2 POST）
 *
 * 设计依据：plan Step 5 + spec §2 P-2
 * - profileType 必填（BABY/ELDER/ADULT/PET/OTHER）
 * - name 必填（最长 50 字符）
 * - relation 必填（与创建人的关系，如"儿子"/"本人"）
 * - avatarEmoji 可空（Service 层按 profileType 兜底：BABY→👶 ELDER→👴 ADULT→👨 PET→🐾 OTHER→👤）
 * - profileData 可空（默认空 Map，后续 P-4 patch 填充）
 * - userId 可空（虚拟成员如宝宝无 user_id）
 *
 * 用 class + setter 风格（匹配 PersonProfileControllerTest 用法 req.setProfileType("BABY")）
 */
public class CreatePersonProfileRequest {
    private String profileType;
    private String name;
    private String relation;
    private String avatarEmoji;
    private String gender;
    private LocalDate birthday;
    private List<String> tags;
    private Map<String, Object> profileData;
    private Long userId;

    public String getProfileType() { return profileType; }
    public void setProfileType(String profileType) { this.profileType = profileType; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRelation() { return relation; }
    public void setRelation(String relation) { this.relation = relation; }
    public String getAvatarEmoji() { return avatarEmoji; }
    public void setAvatarEmoji(String avatarEmoji) { this.avatarEmoji = avatarEmoji; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public LocalDate getBirthday() { return birthday; }
    public void setBirthday(LocalDate birthday) { this.birthday = birthday; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public Map<String, Object> getProfileData() { return profileData; }
    public void setProfileData(Map<String, Object> profileData) { this.profileData = profileData; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
}
