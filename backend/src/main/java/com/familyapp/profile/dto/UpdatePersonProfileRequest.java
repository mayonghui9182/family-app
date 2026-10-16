package com.familyapp.profile.dto;

import java.util.List;
import java.util.Map;

/**
 * 更新人员档案请求 DTO（P-4 PUT）
 *
 * 设计依据：plan Step 5 + spec §2 P-4 + Global Constraints 第 3 条
 * - profileDataPatch: 浅合并到现有 profile_data（existing || new_patch）
 * - removeKeys: 删除 profile_data 顶层 key（不能用 patch 传 null 删除）
 * - patch + removeKeys 同 key → 先 merge 再 remove（最终 key 被删）
 * - removeKeys 删除不存在 key → 幂等无变化（不报错）
 * - 所有字段可选（部分更新）
 *
 * 用 class + setter 风格（匹配 PersonProfileControllerTest 用法 update.setProfileDataPatch(...)）
 */
public class UpdatePersonProfileRequest {
    private Map<String, Object> profileDataPatch;
    private List<String> removeKeys;

    public Map<String, Object> getProfileDataPatch() { return profileDataPatch; }
    public void setProfileDataPatch(Map<String, Object> profileDataPatch) { this.profileDataPatch = profileDataPatch; }
    public List<String> getRemoveKeys() { return removeKeys; }
    public void setRemoveKeys(List<String> removeKeys) { this.removeKeys = removeKeys; }
}
