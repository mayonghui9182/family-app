package com.familyapp.profile;

import com.familyapp.profile.dto.CreatePersonProfileRequest;
import com.familyapp.profile.dto.PersonProfileResponse;
import com.familyapp.profile.dto.UpdatePersonProfileRequest;

import java.util.List;
import java.util.Map;

/**
 * 人员档案 Service 接口
 *
 * 设计依据：plan Step 5-6 + spec §2 P-1~P-5 + ProfilePatchTest
 * - list/get/create/update/delete: P-1~P-5 端点业务
 * - applyProfilePatch: ProfilePatchTest 直接调用，返回 PatchResult
 *   （schema 校验 + JSONB merge + remove_keys + 业务流程不阻断）
 *
 * PatchResult 设计（匹配 ProfilePatchTest API）：
 *   record PatchResult(Map<String, Object> mergedProfileData, List<Discard> discardedKeys)
 *   - mergedProfileData: merge 后的完整 profile_data（existing || validPatch - removeKeys）
 *   - discardedKeys: schema 校验丢弃的 key 列表（不阻断流程）
 */
public interface PersonProfileService {

    /** P-1 GET / 列档案（profileType 可空，空=全类型） */
    List<PersonProfileResponse> list(String profileType);

    /** P-3 GET /{id} 详情 */
    PersonProfileResponse get(Long id);

    /** P-2 POST 创建档案（Service 层兜底 avatar_emoji） */
    PersonProfileResponse create(CreatePersonProfileRequest req);

    /** P-4 PUT /{id} 更新档案（profileDataPatch 浅合并 + removeKeys 删除） */
    PersonProfileResponse update(Long id, UpdatePersonProfileRequest req);

    /** P-5 DELETE /{id} 软删除 */
    void delete(Long id);

    /** ProfilePatchTest 直接调用：patch + removeKeys 处理，返回 PatchResult */
    PatchResult applyProfilePatch(Long familyId, Long profileId,
                                  Map<String, Object> patch, List<String> removeKeys);

    /** patch 处理结果（mergedProfileData + discardedKeys） */
    record PatchResult(Map<String, Object> mergedProfileData, List<Discard> discardedKeys) {}
}
