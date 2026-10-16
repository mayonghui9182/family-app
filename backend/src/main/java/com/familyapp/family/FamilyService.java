package com.familyapp.family;

import com.familyapp.auth.UserContext;
import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.family.dto.ChangeRoleRequest;
import com.familyapp.family.dto.CreateFamilyRequest;
import com.familyapp.family.dto.FamilyResponse;
import com.familyapp.family.dto.FamilySummaryResponse;
import com.familyapp.family.dto.MemberResponse;
import com.familyapp.family.dto.TransferOwnerRequest;
import com.familyapp.family.dto.UpdateFamilyRequest;
import com.familyapp.profile.PersonProfile;
import com.familyapp.profile.PersonProfileRepository;
import com.familyapp.profile.ProfileType;
import com.familyapp.user.User;
import com.familyapp.user.UserRepository;
import com.familyapp.wechat.conversation.WechatFamilyDeletionService;
import com.familyapp.wechat.conversation.WechatChatContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 家庭服务：家庭的增删改查与成员管理（转让、改角色、踢人、退出）。
 */
@Service
public class FamilyService {

    private final FamilyRepository familyRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final UserRepository userRepository;
    private final PersonProfileRepository personProfileRepository;
    private final JdbcTemplate jdbcTemplate;
    private final WechatFamilyDeletionService wechatDeletion;

    public FamilyService(FamilyRepository familyRepository,
                         FamilyMemberRepository familyMemberRepository,
                         UserRepository userRepository,
                         PersonProfileRepository personProfileRepository,
                         JdbcTemplate jdbcTemplate,
                         WechatFamilyDeletionService wechatDeletion) {
        this.familyRepository = familyRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.userRepository = userRepository;
        this.personProfileRepository = personProfileRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.wechatDeletion = wechatDeletion;
    }

    /**
     * 标准入口：从 UserContext.current() 取 ownerId 建家庭 + 自动建 owner 档案。
     * 供 FamilyController HTTP 调用走已登录上下文。
     */
    @Transactional
    public FamilyResponse createFamily(CreateFamilyRequest req) {
        Long ownerId = UserContext.current().userId();
        return createFamilyInternal(req, ownerId);
    }

    /**
     * 重载：显式传入 ownerId（FamilyAutoInitOwnerProfileTest JdbcTemplate 插用户后调，
     * 避免依赖请求上下文）。事务最后插入 owner 档案 1 条（person_profiles）。
     */
    @Transactional
    public FamilyResponse createFamily(CreateFamilyRequest req, Long ownerId) {
        if (ownerId == null) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION, "ownerId 必填");
        }
        return createFamilyInternal(req, ownerId);
    }

    /**
     * 内部实现：创建 Family → FamilyMember(OWNER) → PersonProfile(owner ADULT) 同事务。
     * owner 档案：type=ADULT / userId=owner.id / name=owner.nickname / relation="本人" / avatarEmoji="👨" / createdBy=owner.id
     */
    private FamilyResponse createFamilyInternal(CreateFamilyRequest req, Long ownerId) {
        Instant now = Instant.now();

        // 1) Family
        Family family = new Family();
        family.setName(req.getName());
        family.setDescription(req.getDescription());
        family.setAvatar(req.getAvatar());
        family.setCreatedBy(ownerId);
        family.setCreatedAt(now);
        family.setUpdatedAt(now);
        familyRepository.save(family);

        // 2) FamilyMember OWNER
        FamilyMember member = new FamilyMember();
        member.setFamilyId(family.getId());
        member.setUserId(ownerId);
        member.setRole(FamilyRole.OWNER);
        member.setJoinedAt(now);
        familyMemberRepository.save(member);

        // 3) owner 档案（Change 8：家庭创建成功后自动为 owner 建 1 条档案）
        User owner = userRepository.findById(ownerId).orElse(null);
        String ownerNickname = (owner != null && owner.getNickname() != null) ? owner.getNickname() : "本人";
        PersonProfile ownerProfile = new PersonProfile();
        ownerProfile.setFamilyId(family.getId());
        ownerProfile.setUserId(ownerId);
        ownerProfile.setProfileType(ProfileType.ADULT);
        ownerProfile.setName(ownerNickname);
        ownerProfile.setAvatarEmoji("👨"); // ADULT 兜底（对应 Service 层兜底规则）
        ownerProfile.setRelation("本人");
        ownerProfile.setCreatedBy(ownerId);
        personProfileRepository.save(ownerProfile);

        // 4) 响应：memberResponse 与原逻辑一致
        MemberResponse memberResponse = new MemberResponse(ownerId, ownerNickname,
                FamilyRole.OWNER, FamilyRole.OWNER.getChineseName(), now);
        return new FamilyResponse(family.getId(), family.getName(), family.getDescription(),
                family.getAvatar(), List.of(memberResponse));
    }

    @Transactional(readOnly = true)
    public List<FamilySummaryResponse> listMyFamilies() {
        Long userId = UserContext.current().userId();
        List<FamilyMember> myMembers = familyMemberRepository.findAllByUserIdAndLeftAtIsNull(userId);
        if (myMembers.isEmpty()) {
            return List.of();
        }
        List<Long> familyIds = new ArrayList<>();
        for (FamilyMember m : myMembers) {
            familyIds.add(m.getFamilyId());
        }
        Map<Long, Family> familyMap = familyRepository.findByIdInAndDeletedAtIsNull(familyIds).stream()
                .collect(Collectors.toMap(Family::getId, f -> f));
        List<FamilySummaryResponse> result = new ArrayList<>();
        for (FamilyMember m : myMembers) {
            Family f = familyMap.get(m.getFamilyId());
            if (f == null) {
                continue;
            }
            result.add(new FamilySummaryResponse(f.getId(), f.getName(), m.getRole(),
                    m.getRole().getChineseName(), m.getJoinedAt()));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public FamilyResponse getFamilyDetail(Long id) {
        currentRoleOrThrow(id);
        Family family = loadFamilyOrThrow(id);
        List<FamilyMember> members = familyMemberRepository.findAllByFamilyIdAndLeftAtIsNull(id);
        return new FamilyResponse(family.getId(), family.getName(), family.getDescription(),
                family.getAvatar(), toMemberResponses(members));
    }

    @Transactional
    public FamilyResponse updateFamily(Long id, UpdateFamilyRequest req) {
        lockFamilyOrThrow(id);
        FamilyRole role = currentRoleOrThrow(id);
        if (role != FamilyRole.OWNER && role != FamilyRole.ADULT) {
            throw new ApiException(ResultCode.ROLE_NO_PERMISSION, "您的角色不能修改家庭信息");
        }
        Family family = loadFamilyOrThrow(id);
        if (req.getName() != null) {
            family.setName(req.getName());
        }
        if (req.getDescription() != null) {
            family.setDescription(req.getDescription());
        }
        if (req.getAvatar() != null) {
            family.setAvatar(req.getAvatar());
        }
        family.setUpdatedAt(Instant.now());
        familyRepository.save(family);
        List<FamilyMember> members = familyMemberRepository.findAllByFamilyIdAndLeftAtIsNull(id);
        return new FamilyResponse(family.getId(), family.getName(), family.getDescription(),
                family.getAvatar(), toMemberResponses(members));
    }

    @Transactional
    public void deleteFamily(Long id) {
        List<WechatChatContext> routes = wechatDeletion.lockRoutes(id);
        lockFamilyOrThrow(id);
        FamilyRole role = currentRoleOrThrow(id);
        if (role != FamilyRole.OWNER) {
            throw new ApiException(ResultCode.FAMILY_DELETE_NOT_OWNER);
        }
        Long userId = UserContext.current().userId();
        List<FamilyMember> myMemberships = familyMemberRepository.findAllByUserIdAndLeftAtIsNull(userId);
        List<Long> allMyFamilyIds = myMemberships.stream()
                .map(FamilyMember::getFamilyId).distinct().toList();
        long myActiveFamilies = familyRepository.findByIdInAndDeletedAtIsNull(allMyFamilyIds).size();
        if (myActiveFamilies <= 1L) {
            throw new ApiException(ResultCode.FAMILY_DELETE_LAST_FAMILY);
        }
        Instant now = Instant.now();
        Family family = loadFamilyOrThrow(id);
        family.setDeletedAt(now);
        family.setUpdatedAt(now);
        familyRepository.save(family);
        List<FamilyMember> activeMembers = familyMemberRepository.findAllByFamilyIdAndLeftAtIsNull(id);
        for (FamilyMember m : activeMembers) {
            m.setLeftAt(now);
        }
        familyMemberRepository.saveAll(activeMembers);
        wechatDeletion.softDelete(id, routes, now);
    }

    @Transactional
    public void leaveFamily(Long id) {
        List<WechatChatContext> routes = wechatDeletion.lockRoutes(id);
        Family family = lockFamilyOrThrow(id);
        FamilyRole role = currentRoleOrThrow(id);
        if (role == FamilyRole.OWNER) {
            List<FamilyMember> activeMembers = familyMemberRepository.findAllByFamilyIdAndLeftAtIsNull(id);
            if (activeMembers.size() != 1) {
                throw new ApiException(ResultCode.OWNER_CANNOT_LEAVE,
                        "请先转让管理员身份，或移除其他成员后再退出");
            }
            deleteEmptyFamily(family, routes);
            return;
        }
        FamilyMember member = requireActiveMember(id);
        member.setLeftAt(Instant.now());
        familyMemberRepository.save(member);
    }

    /**
     * 空家庭只会由唯一管理员退出触发。先删三个历史外键为 RESTRICT 的业务表，
     * 再物理删除家庭；其余 family_id 关联表通过数据库 ON DELETE CASCADE 同事务清理。
     */
    private void deleteEmptyFamily(Family family, List<WechatChatContext> routes) {
        Long familyId = family.getId();
        wechatDeletion.physicalDelete(familyId, routes);
        jdbcTemplate.update("DELETE FROM task_notifications WHERE family_id = ?", familyId);
        jdbcTemplate.update("DELETE FROM wechat_inbound_message WHERE family_id = ?", familyId);
        jdbcTemplate.update("DELETE FROM tasks WHERE family_id = ?", familyId);
        jdbcTemplate.update("DELETE FROM categories WHERE family_id = ?", familyId);
        jdbcTemplate.update("DELETE FROM person_profiles WHERE family_id = ?", familyId);
        jdbcTemplate.update("DELETE FROM family_members WHERE family_id = ?", familyId);
        familyRepository.delete(family);
        familyRepository.flush();
    }

    @Transactional
    public void transferOwner(Long id, TransferOwnerRequest req) {
        lockFamilyOrThrow(id);
        FamilyRole role = currentRoleOrThrow(id);
        if (role != FamilyRole.OWNER) {
            throw new ApiException(ResultCode.ROLE_NO_PERMISSION);
        }
        Long callerId = UserContext.current().userId();
        Long targetUserId = req.getTargetUserId();
        if (targetUserId.equals(callerId)) {
            throw new ApiException(ResultCode.OWNER_TRANSFER_TARGET_INVALID);
        }
        FamilyMember target = familyMemberRepository
                .findByFamilyIdAndUserIdAndLeftAtIsNull(id, targetUserId)
                .orElseThrow(() -> new ApiException(ResultCode.OWNER_TRANSFER_TARGET_INVALID));
        FamilyMember caller = requireActiveMember(id);
        caller.setRole(FamilyRole.ADULT);
        target.setRole(FamilyRole.OWNER);
        familyMemberRepository.save(caller);
        familyMemberRepository.save(target);
    }

    @Transactional
    public void changeRole(Long familyId, Long userId, ChangeRoleRequest req) {
        lockFamilyOrThrow(familyId);
        FamilyRole role = currentRoleOrThrow(familyId);
        if (role != FamilyRole.OWNER) {
            throw new ApiException(ResultCode.ROLE_NO_PERMISSION);
        }
        FamilyRole newRole;
        try {
            newRole = FamilyRole.valueOf(req.getRole().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ResultCode.ROLE_NO_PERMISSION);
        }
        if (newRole != FamilyRole.ADULT && newRole != FamilyRole.CHILD) {
            throw new ApiException(ResultCode.ROLE_NO_PERMISSION);
        }
        FamilyMember target = familyMemberRepository
                .findByFamilyIdAndUserIdAndLeftAtIsNull(familyId, userId)
                .orElseThrow(() -> new ApiException(ResultCode.FAMILY_NOT_MEMBER));
        if (target.getRole() == FamilyRole.OWNER) {
            throw new ApiException(ResultCode.ROLE_NO_PERMISSION);
        }
        target.setRole(newRole);
        familyMemberRepository.save(target);
    }

    @Transactional
    public void kickMember(Long familyId, Long userId) {
        lockFamilyOrThrow(familyId);
        FamilyRole role = currentRoleOrThrow(familyId);
        if (role != FamilyRole.OWNER) {
            throw new ApiException(ResultCode.ROLE_NO_PERMISSION);
        }
        FamilyMember target = familyMemberRepository
                .findByFamilyIdAndUserIdAndLeftAtIsNull(familyId, userId)
                .orElseThrow(() -> new ApiException(ResultCode.FAMILY_NOT_MEMBER));
        if (target.getRole() == FamilyRole.OWNER) {
            throw new ApiException(ResultCode.ROLE_NO_PERMISSION,
                    "不能踢出家庭管理员，请先转让管理员身份");
        }
        target.setLeftAt(Instant.now());
        familyMemberRepository.save(target);
    }

    /** 成员变更先锁家庭，再读取角色，避免等待锁时保留旧权限和旧成员数。 */
    private Family lockFamilyOrThrow(Long id) {
        return familyRepository.findActiveByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(ResultCode.FAMILY_NOT_FOUND));
    }

    private Family loadFamilyOrThrow(Long id) {
        return familyRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ApiException(ResultCode.FAMILY_NOT_FOUND));
    }

    private FamilyMember requireActiveMember(Long familyId) {
        Long userId = UserContext.current().userId();
        return familyMemberRepository
                .findByFamilyIdAndUserIdAndLeftAtIsNull(familyId, userId)
                .orElseThrow(() -> new ApiException(ResultCode.FAMILY_NOT_MEMBER));
    }

    private FamilyRole currentRoleOrThrow(Long familyId) {
        loadFamilyOrThrow(familyId);
        return requireActiveMember(familyId).getRole();
    }

    private List<MemberResponse> toMemberResponses(List<FamilyMember> members) {
        if (members.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = new ArrayList<>();
        for (FamilyMember m : members) {
            userIds.add(m.getUserId());
        }
        Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        List<MemberResponse> result = new ArrayList<>();
        for (FamilyMember m : members) {
            User u = userMap.get(m.getUserId());
            String nickname = u != null ? u.getNickname() : null;
            result.add(new MemberResponse(m.getUserId(), nickname, m.getRole(),
                    m.getRole().getChineseName(), m.getJoinedAt()));
        }
        return result;
    }
}
