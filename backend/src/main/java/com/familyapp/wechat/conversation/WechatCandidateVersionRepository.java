package com.familyapp.wechat.conversation;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/** 不可变候选版本仓储；所有读取都包含 familyId。 */
public interface WechatCandidateVersionRepository extends Repository<WechatCandidateVersion, Long> {

    <S extends WechatCandidateVersion> S save(S entity);

    Optional<WechatCandidateVersion> findByIdAndFamilyId(Long id, Long familyId);

    Optional<WechatCandidateVersion> findByConversationIdAndFamilyIdAndRevision(
            Long conversationId, Long familyId, Integer revision);

    Optional<WechatCandidateVersion> findByConversationIdAndFamilyIdAndSourceMessageId(
            Long conversationId, Long familyId, Long sourceMessageId);

    List<WechatCandidateVersion> findByConversationIdAndFamilyIdOrderByRevisionDesc(
            Long conversationId, Long familyId);
}

