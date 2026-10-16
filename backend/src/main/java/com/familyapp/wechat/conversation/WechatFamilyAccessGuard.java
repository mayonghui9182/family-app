package com.familyapp.wechat.conversation;

import com.familyapp.family.Family;
import com.familyapp.family.FamilyMember;
import com.familyapp.family.FamilyMemberRepository;
import com.familyapp.family.FamilyRepository;
import com.familyapp.family.FamilyRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 微信会话家庭实时访问守卫。
 *
 * <p>每次调用都重新读取有效成员关系和未删除家庭；当前家庭、历史会话及缓存角色
 * 都不能单独授予访问权。</p>
 */
@Service
public class WechatFamilyAccessGuard {
    @org.springframework.beans.factory.annotation.Autowired
    private WechatConversationOperationalMetrics metrics;

    private final FamilyMemberRepository memberRepository;
    private final FamilyRepository familyRepository;

    public WechatFamilyAccessGuard(
            FamilyMemberRepository memberRepository,
            FamilyRepository familyRepository) {
        this.memberRepository = memberRepository;
        this.familyRepository = familyRepository;
    }

    /**
     * 返回当前实时权限；任何无权原因都折叠为空，调用方不得据此暴露家庭存在性。
     */
    @Transactional(readOnly = true)
    public Optional<FamilyAccess> findAccess(long userId, long familyId) {
        Optional<FamilyMember> membership =
                memberRepository.findByFamilyIdAndUserIdAndLeftAtIsNull(
                        familyId,
                        userId);
        if (membership.isEmpty()) {
            return Optional.empty();
        }

        Optional<Family> family =
                familyRepository.findByIdAndDeletedAtIsNull(familyId);
        if (family.isEmpty() || membership.get().getRole() == null) {
            return Optional.empty();
        }

        FamilyRole role = membership.get().getRole();
        return Optional.of(new FamilyAccess(
                familyId,
                family.get().getName(),
                role,
                role.getChineseName()));
    }

    /**
     * 要求操作发生在当前有效家庭中，并返回最新角色供后续统一业务入口校验。
     */
    @Transactional(readOnly = true)
    public FamilyAccess requireAccess(
            long userId,
            long familyId,
            AccessOperation operation) {
        return findAccess(userId, familyId)
                .orElseThrow(() -> {
                    if (metrics != null) metrics.event(WechatConversationOperationalMetrics.Event.CROSS_FAMILY_DENIED);
                    return new WechatFamilyAccessDeniedException();
                });
    }

    public enum AccessOperation {
        SELECT_FAMILY,
        ROUTE_MESSAGE,
        RESUME_CONVERSATION,
        READ_HISTORY,
        CONFIRM_TASK
    }

    public record FamilyAccess(
            long familyId,
            String familyName,
            FamilyRole role,
            String roleName) {
    }
}
