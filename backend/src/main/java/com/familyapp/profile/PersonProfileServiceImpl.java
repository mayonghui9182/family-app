package com.familyapp.profile;

import com.familyapp.auth.UserContext;
import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.family.FamilyRepository;
import com.familyapp.profile.dto.CreatePersonProfileRequest;
import com.familyapp.profile.dto.PersonProfileResponse;
import com.familyapp.profile.dto.UpdatePersonProfileRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 人员档案 Service 实现
 *
 * <p>设计依据：plan Step 5 + spec §2 P-1~P-5 + ProfilePatchTest
 *
 * <p><b>核心要点：</b>
 * <ol>
 *   <li>avatar_emoji 兜底（Service 层）—— request 为 null/blank 时按 profile_type 赋值：
 *       BABY→"👶" / ELDER→"👴" / ADULT→"👨" / PET→"🐾" / OTHER→"👤"，保证 NOT NULL 列永不为空</li>
 *   <li>applyProfilePatch：ProfilePatchTest 直调入口 → ProfileDataSchema.validatePatch 白名单校验
 *       → validPatch 浅合并到 existing profile_data → removeKeys 删除顶层 key（幂等，不存在 key 不报错）
 *       → 丢弃的 key 汇总为 PatchResult.discardedKeys（不阻断业务，AI 反复沟通场景需要此行为）</li>
 *   <li>权限守卫：P-2/P-4 前 checkWriteAccess（CHILD→40303）；P-5 前 checkDeleteAccess（非 OWNER→40304）</li>
 *   <li>family_id 多租户隔离：所有查询走 findByFamilyId*，单档查不到抛 PROFILE_NOT_FOUND(41000)</li>
 * </ol>
 */
@Service
@Transactional
public class PersonProfileServiceImpl implements PersonProfileService {

    private final PersonProfileRepository repo;
    private final ProfileRoleGuard roleGuard;
    private final ProfileDataSchema schema;
    private final FamilyRepository families;

    public PersonProfileServiceImpl(PersonProfileRepository repo, ProfileRoleGuard roleGuard, FamilyRepository families) {
        this.repo = repo;
        this.roleGuard = roleGuard;
        this.schema = new ProfileDataSchema();
        this.families = families;
    }

    // ==================== P-1：列档案 ====================

    @Override
    @Transactional(readOnly = true)
    public List<PersonProfileResponse> list(String profileType) {
        Long familyId = getCurrentFamilyId();
        List<PersonProfile> list;
        if (profileType == null || profileType.isBlank()) {
            list = repo.findByFamilyIdAndDeletedAtIsNull(familyId);
        } else {
            ProfileType pt = parseProfileType(profileType);
            list = repo.findByFamilyIdAndProfileTypeAndDeletedAtIsNull(familyId, pt);
        }
        return list.stream().map(PersonProfileResponse::from).toList();
    }

    // ==================== P-3：详情 ====================

    @Override
    @Transactional(readOnly = true)
    public PersonProfileResponse get(Long id) {
        PersonProfile pp = findOrFail(id);
        return PersonProfileResponse.from(pp);
    }

    // ==================== P-2：创建 ====================

    @Override
    public PersonProfileResponse create(CreatePersonProfileRequest req) {
        roleGuard.checkWriteAccess(getCurrentRole());

        // 必填校验（虽有 @Valid，但 SpringBoot Test 未启动时需要防御）
        if (req.getName() == null || req.getName().isBlank()) {
            throw new ApiException(ResultCode.PROFILE_NAME_EMPTY);
        }
        if (req.getRelation() == null || req.getRelation().isBlank()) {
            throw new ApiException(ResultCode.PROFILE_RELATION_EMPTY);
        }
        ProfileType profileType = parseProfileType(req.getProfileType());

        Long familyId = getCurrentFamilyId();
        Long currentUserId = getCurrentUserId();

        // 与邀请加入的自动建档串行，避免检查重名后被另一请求占用名称。
        families.findActiveByIdForUpdate(familyId)
                .orElseThrow(() -> new ApiException(ResultCode.FAMILY_NOT_FOUND));

        PersonProfile pp = new PersonProfile();
        pp.setFamilyId(familyId);
        pp.setUserId(req.getUserId());
        pp.setProfileType(profileType);
        pp.setName(req.getName().trim());
        // ★ avatar_emoji 兜底 Service 层（NOT NULL 列保证永不空）
        pp.setAvatarEmoji(resolveAvatarEmoji(req.getAvatarEmoji(), profileType));
        pp.setGender(req.getGender());
        pp.setBirthday(req.getBirthday());
        pp.setRelation(req.getRelation().trim());
        pp.setTags(req.getTags() != null ? req.getTags() : List.of());

        // profileData 先经白名单校验（非法 key 静默 discard，不阻断）
        Map<String, Object> dataPatch = req.getProfileData() != null ? req.getProfileData() : Map.of();
        ValidationResult vr = schema.validatePatch(profileType.name(), dataPatch);
        pp.setProfileData(new HashMap<>(vr.validPatch()));

        pp.setCreatedBy(currentUserId);
        return PersonProfileResponse.from(repo.save(pp));
    }

    // ==================== P-4：更新 ====================

    @Override
    public PersonProfileResponse update(Long id, UpdatePersonProfileRequest req) {
        roleGuard.checkWriteAccess(getCurrentRole());
        PersonProfile pp = findOrFail(id);

        // P-4 MVP 专注 profile_data JSONB 维护：仅 patch + removeKeys；
        // 基础字段（name/avatar/relation 等）部分更新暂不暴露（需要时在 DTO 加字段）。
        Map<String, Object> patch = req.getProfileDataPatch() != null ? req.getProfileDataPatch() : Map.of();
        List<String> removeKeys = req.getRemoveKeys() != null ? req.getRemoveKeys() : List.of();

        PatchResult pr = applyProfilePatchInternal(pp, patch, removeKeys);
        pp.setProfileData(pr.mergedProfileData());
        // discardedKeys 不阻断，也不存入 DB（只在 API 响应时带出来）

        return PersonProfileResponse.from(repo.save(pp));
    }

    // ==================== P-5：软删除 ====================

    @Override
    public void delete(Long id) {
        roleGuard.checkDeleteAccess(getCurrentRole());
        PersonProfile pp = findOrFail(id);
        roleGuard.checkDeleteReferenced(pp.getFamilyId(), pp.getId());
        pp.setDeletedAt(Instant.now());
        repo.save(pp);
    }

    // ==================== ProfilePatchTest 直调：patch + removeKeys ====================

    @Override
    public PatchResult applyProfilePatch(Long familyId, Long profileId,
                                         Map<String, Object> patch, List<String> removeKeys) {
        // 内部合并不依赖请求上下文，但必须显式携带家庭作用域，避免裸 profileId 跨家庭写入
        PersonProfile pp = repo.findByFamilyIdAndIdAndDeletedAtIsNull(familyId, profileId)
                .orElseThrow(() -> new ApiException(ResultCode.PROFILE_NOT_FOUND));
        PatchResult pr = applyProfilePatchInternal(pp, patch, removeKeys);
        pp.setProfileData(pr.mergedProfileData());
        repo.save(pp);
        return pr;
    }

    // ==================== 内部工具 ====================

    /**
     * 内部 patch 处理核心（不写 DB，只返回合并后的 profile_data + discardedKeys）。
     * 流程：schema.validatePatch → validPatch 浅合并 → removeKeys 删除 → 返回 PatchResult。
     */
    private PatchResult applyProfilePatchInternal(PersonProfile pp,
                                                   Map<String, Object> patch,
                                                   List<String> removeKeys) {
        String typeName = pp.getProfileType().name();
        Map<String, Object> existing = pp.getProfileData() != null
                ? new HashMap<>(pp.getProfileData())
                : new HashMap<>();

        // 1) 白名单校验：非法 key / 类型错 / range 超界 → discard + 不合并
        ValidationResult vr = schema.validatePatch(typeName, patch != null ? patch : Map.of());
        for (Map.Entry<String, Object> e : vr.validPatch().entrySet()) {
            existing.put(e.getKey(), e.getValue()); // 浅合并：existing || validPatch
        }

        // 2) removeKeys 删除顶层 key（不存在 key 静默跳过 → 幂等）
        List<String> removes = removeKeys != null ? removeKeys : List.of();
        for (String k : removes) {
            existing.remove(k);
        }

        return new PatchResult(Map.copyOf(existing), new ArrayList<>(vr.discardedKeys()));
    }

    /** 按 profile_type 兜底 avatar_emoji；传入非空则优先用传入值 */
    private String resolveAvatarEmoji(String customEmoji, ProfileType type) {
        if (customEmoji != null && !customEmoji.isBlank()) {
            return customEmoji;
        }
        return switch (type) {
            case BABY -> "👶";
            case ELDER -> "👴";
            case ADULT -> "👨";
            case PET -> "🐾";
            case OTHER -> "👤";
        };
    }

    /** 解析档案类型，非法值抛 PROFILE_TYPE_INVALID */
    private ProfileType parseProfileType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ResultCode.PROFILE_TYPE_INVALID);
        }
        try {
            return ProfileType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ResultCode.PROFILE_TYPE_INVALID);
        }
    }

    /** 按 family_id + id + deleted_at IS NULL 查单档，找不到抛 41000 */
    private PersonProfile findOrFail(Long id) {
        return repo.findByFamilyIdAndIdAndDeletedAtIsNull(getCurrentFamilyId(), id)
                .orElseThrow(() -> new ApiException(ResultCode.PROFILE_NOT_FOUND));
    }

    private Long getCurrentFamilyId() {
        Long fid = UserContext.current().familyId();
        if (fid == null) {
            throw new ApiException(ResultCode.FAMILY_HEADER_REQUIRED);
        }
        return fid;
    }

    private String getCurrentRole() {
        return UserContext.current().role();
    }

    private Long getCurrentUserId() {
        return UserContext.current().userId();
    }
}
