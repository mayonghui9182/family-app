package com.familyapp.wechat.conversation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 微信会话受保护数据读取器。
 *
 * <p>摘要、候选和家庭经验在访问仓储前统一重新验证成员关系与家庭状态，
 * 并继续使用 familyId 作为仓储查询条件。</p>
 */
@Service
public class WechatConversationProtectedDataReader {

    private final WechatFamilyAccessGuard accessGuard;
    private final WechatTaskConversationRepository conversationRepository;
    private final WechatCandidateVersionRepository candidateRepository;
    private final FamilyTaskExperienceRepository experienceRepository;

    public WechatConversationProtectedDataReader(
            WechatFamilyAccessGuard accessGuard,
            WechatTaskConversationRepository conversationRepository,
            WechatCandidateVersionRepository candidateRepository,
            FamilyTaskExperienceRepository experienceRepository) {
        this.accessGuard = accessGuard;
        this.conversationRepository = conversationRepository;
        this.candidateRepository = candidateRepository;
        this.experienceRepository = experienceRepository;
    }

    @Transactional(readOnly = true)
    public List<WechatTaskConversation> listHistory(
            long wechatBindId,
            long userId,
            long familyId) {
        accessGuard.requireAccess(
                userId,
                familyId,
                WechatFamilyAccessGuard.AccessOperation.READ_HISTORY);
        return conversationRepository
                .findTop5ByWechatBindIdAndFamilyIdAndDeletedAtIsNullOrderByLastActivityAtDesc(
                        wechatBindId,
                        familyId);
    }

    @Transactional(readOnly = true)
    public Optional<WechatCandidateVersion> findCandidate(
            long conversationId,
            int revision,
            long userId,
            long familyId) {
        accessGuard.requireAccess(
                userId,
                familyId,
                WechatFamilyAccessGuard.AccessOperation.CONFIRM_TASK);
        return candidateRepository
                .findByConversationIdAndFamilyIdAndRevision(
                        conversationId,
                        familyId,
                        revision);
    }

    @Transactional(readOnly = true)
    public List<FamilyTaskExperience> listActiveExperiences(
            long userId,
            long familyId) {
        accessGuard.requireAccess(
                userId,
                familyId,
                WechatFamilyAccessGuard.AccessOperation.ROUTE_MESSAGE);
        return experienceRepository
                .findByFamilyIdAndStatusOrderByUpdatedAtDesc(
                        familyId,
                        ExperienceStatus.ACTIVE);
    }
}
