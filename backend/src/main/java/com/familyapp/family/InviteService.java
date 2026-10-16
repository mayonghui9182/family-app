package com.familyapp.family;

import com.familyapp.auth.UserContext;
import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.family.dto.AcceptInviteRequest;
import com.familyapp.family.dto.InviteCreateRequest;
import com.familyapp.family.dto.InviteResponse;
import com.familyapp.profile.PersonProfile;
import com.familyapp.profile.PersonProfileRepository;
import com.familyapp.profile.ProfileType;
import com.familyapp.user.User;
import com.familyapp.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 邀请码服务：创建、列表、取消、接受（加入家庭）。
 */
@Service
public class InviteService {

    private final InviteRepository inviteRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final FamilyRepository familyRepository;
    private final InviteCodeGenerator inviteCodeGenerator;
    private final PersonProfileRepository personProfileRepository;
    private final UserRepository userRepository;

    public InviteService(InviteRepository inviteRepository,
                         FamilyMemberRepository familyMemberRepository,
                         FamilyRepository familyRepository,
                         InviteCodeGenerator inviteCodeGenerator,
                         PersonProfileRepository personProfileRepository,
                         UserRepository userRepository) {
        this.inviteRepository = inviteRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.familyRepository = familyRepository;
        this.inviteCodeGenerator = inviteCodeGenerator;
        this.personProfileRepository = personProfileRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public InviteResponse createInvite(Long familyId, InviteCreateRequest req) {
        lockFamilyOrThrow(familyId);
        FamilyRole role = callerRoleOrThrow(familyId);
        if (role == FamilyRole.CHILD) {
            throw new ApiException(ResultCode.ROLE_NO_PERMISSION,
                    "您的角色不能创建邀请，请让家长帮忙操作");
        }
        Integer maxUses = req.getMaxUses() == null ? 1 : req.getMaxUses();
        if (maxUses < 1 || maxUses > 100) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION);
        }
        Integer ttlDays = req.getTtlDays() == null ? 7 : req.getTtlDays();
        if (ttlDays < 1 || ttlDays > 30) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION);
        }
        Instant now = Instant.now();
        Invite invite = new Invite();
        invite.setFamilyId(familyId);
        invite.setInviterId(UserContext.current().userId());
        invite.setCode(inviteCodeGenerator.next());
        invite.setMaxUses(maxUses);
        invite.setUsedCount(0);
        invite.setCreatedAt(now);
        invite.setExpiresAt(now.plus(Duration.ofDays(ttlDays)));
        invite.setStatus(InviteStatus.ACTIVE);
        inviteRepository.save(invite);
        return toInviteResponse(invite);
    }

    @Transactional(readOnly = true)
    public List<InviteResponse> listInvites(Long familyId, InviteStatus status) {
        FamilyRole role = callerRoleOrThrow(familyId);
        if (role == FamilyRole.CHILD) {
            throw new ApiException(ResultCode.ROLE_NO_PERMISSION);
        }
        List<Invite> invites = status != null
                ? inviteRepository.findAllByFamilyIdAndStatus(familyId, status)
                : inviteRepository.findAllByFamilyId(familyId);
        return invites.stream().map(this::toInviteResponse).toList();
    }

    @Transactional
    public void cancelInvite(Long familyId, Long inviteId) {
        lockFamilyOrThrow(familyId);
        FamilyRole role = callerRoleOrThrow(familyId);
        Invite invite = inviteRepository.findById(inviteId)
                .orElseThrow(() -> new ApiException(ResultCode.INVITE_NOT_FOUND));
        if (!invite.getFamilyId().equals(familyId)) {
            throw new ApiException(ResultCode.INVITE_NOT_FOUND);
        }
        Long callerId = UserContext.current().userId();
        boolean canCancel = role == FamilyRole.OWNER
                || (role == FamilyRole.ADULT && invite.getInviterId().equals(callerId));
        if (!canCancel) {
            throw new ApiException(ResultCode.ROLE_NO_PERMISSION);
        }
        invite.setStatus(InviteStatus.CANCELLED);
        inviteRepository.save(invite);
    }

    @Transactional
    public void acceptInvite(AcceptInviteRequest req) {
        acceptInviteForUser(req.getCode(), UserContext.current().userId());
    }

    /**
     * 用指定账号接受邀请，供注册事务复用；返回加入的家庭 ID。
     * 新用户尚未建立 UserContext，不能走 HTTP 入口的当前用户读取逻辑。
     */
    @Transactional
    public Long acceptInviteForUser(String rawCode, Long userId) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase();
        Long familyId = inviteRepository.findFamilyIdByCodeAndStatus(code, InviteStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(ResultCode.INVITE_NOT_FOUND));
        lockFamilyOrThrow(familyId);
        Invite invite = inviteRepository.findByFamilyIdAndCodeAndStatus(familyId, code, InviteStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(ResultCode.INVITE_NOT_FOUND));
        Instant now = Instant.now();
        if (invite.getStatus() == InviteStatus.ACTIVE && now.isAfter(invite.getExpiresAt())) {
            invite.setStatus(InviteStatus.EXPIRED);
            inviteRepository.save(invite);
            throw new ApiException(ResultCode.INVITE_EXPIRED);
        }
        if (invite.getStatus() == InviteStatus.ACTIVE
                && invite.getUsedCount() >= invite.getMaxUses()) {
            invite.setStatus(InviteStatus.USED);
            inviteRepository.save(invite);
            throw new ApiException(ResultCode.INVITE_COUNT_FULL);
        }
        if (familyMemberRepository
                .findByFamilyIdAndUserIdAndLeftAtIsNull(invite.getFamilyId(), userId)
                .isPresent()) {
            throw new ApiException(ResultCode.INVITE_ALREADY_MEMBER);
        }
        invite.setUsedCount(invite.getUsedCount() + 1);
        if (invite.getUsedCount() >= invite.getMaxUses()) {
            invite.setStatus(InviteStatus.USED);
        }
        invite.setAcceptedAt(now);
        inviteRepository.save(invite);

        FamilyMember leftMember = null;
        for (FamilyMember m : familyMemberRepository.findAllByFamilyId(invite.getFamilyId())) {
            if (m.getUserId().equals(userId) && m.getLeftAt() != null) {
                leftMember = m;
                break;
            }
        }
        if (leftMember != null) {
            leftMember.setLeftAt(null);
            leftMember.setJoinedAt(now);
            familyMemberRepository.save(leftMember);
        } else {
            FamilyMember newMember = new FamilyMember();
            newMember.setFamilyId(invite.getFamilyId());
            newMember.setUserId(userId);
            newMember.setRole(FamilyRole.ADULT);
            newMember.setJoinedAt(now);
            familyMemberRepository.save(newMember);
        }
        ensureUserProfile(invite.getFamilyId(), userId);
        return invite.getFamilyId();
    }

    /** 新加入的账号也要有本人档案，才能被家庭任务正确指派。 */
    private void ensureUserProfile(Long familyId, Long userId) {
        if (personProfileRepository.findByFamilyIdAndUserIdAndDeletedAtIsNull(familyId, userId).isPresent()) {
            return;
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ResultCode.UNAUTHORIZED));
        PersonProfile profile = new PersonProfile();
        profile.setFamilyId(familyId);
        profile.setUserId(userId);
        profile.setProfileType(ProfileType.ADULT);
        profile.setName(availableProfileName(familyId, user.getNickname()));
        profile.setAvatarEmoji("👨");
        profile.setRelation("本人");
        profile.setCreatedBy(userId);
        personProfileRepository.save(profile);
    }

    /** 家庭锁内选取未占用名称，不把同名的其他人或虚拟档案误绑定到新账号。 */
    private String availableProfileName(Long familyId, String nickname) {
        Set<String> names = personProfileRepository.findByFamilyIdAndDeletedAtIsNull(familyId)
                .stream().map(PersonProfile::getName).collect(Collectors.toSet());
        String base = nickname == null || nickname.isBlank() ? "家人" : nickname.trim();
        String candidate = truncateName(base, 50);
        for (int number = 2; names.contains(candidate); number++) {
            String suffix = "（" + number + "）";
            candidate = truncateName(base, 50 - suffix.length()) + suffix;
        }
        return candidate;
    }

    /** 与请求长度校验保持一致，并避免在 UTF-16 代理对中间切断表情字符。 */
    private String truncateName(String value, int limit) {
        int end = Math.min(limit, value.length());
        if (end < value.length() && end > 0 && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

    /** 与退出、解散、角色变更共享家庭行锁，事务提交前保持成员与邀请状态一致。 */
    private void lockFamilyOrThrow(Long familyId) {
        familyRepository.findActiveByIdForUpdate(familyId)
                .orElseThrow(() -> new ApiException(ResultCode.FAMILY_NOT_FOUND));
    }

    private FamilyRole callerRoleOrThrow(Long familyId) {
        familyRepository.findByIdAndDeletedAtIsNull(familyId)
                .orElseThrow(() -> new ApiException(ResultCode.FAMILY_NOT_FOUND));
        Long userId = UserContext.current().userId();
        return familyMemberRepository
                .findByFamilyIdAndUserIdAndLeftAtIsNull(familyId, userId)
                .orElseThrow(() -> new ApiException(ResultCode.FAMILY_NOT_MEMBER))
                .getRole();
    }

    private InviteResponse toInviteResponse(Invite invite) {
        return new InviteResponse(invite.getId(), invite.getCode(), invite.getMaxUses(),
                invite.getUsedCount(), invite.getExpiresAt(), invite.getCreatedAt(),
                invite.getStatus());
    }
}
