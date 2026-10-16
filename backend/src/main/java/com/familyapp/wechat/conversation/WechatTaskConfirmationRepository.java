package com.familyapp.wechat.conversation;

import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 确认凭据仓储；任何读取都必须携带 familyId。 */
public interface WechatTaskConfirmationRepository extends Repository<WechatTaskConfirmation, Long> {

    <S extends WechatTaskConfirmation> S save(S entity);

    /** 仅从已提交确认中恢复结果消息，读取始终限定当前家庭。 */
    @Query("""
            select c from WechatTaskConfirmation c
             where c.familyId = :familyId and c.confirmationMessageId is not null
               and exists (select v.id from WechatTaskConversation v where v.id = c.conversationId
                           and v.familyId = :familyId and v.deletedAt is null)
               and not exists (select o.id from WechatMessageOutbox o where o.familyId = :familyId
                           and o.conversationId = c.conversationId
                           and o.dedupeKey = concat(c.idempotencyKey, ':result'))
             order by c.id
            """)
    List<WechatTaskConfirmation> missingResults(@Param("familyId") Long familyId, Pageable page);

    @Query("""
            select c from WechatTaskConfirmation c
             where c.familyId = :familyId and c.confirmedBy = :userId
               and exists (select v.id from WechatTaskConversation v where v.id = c.conversationId
                 and v.familyId = :familyId and v.userId = :userId
                 and v.wechatBindId = :bindId and v.deletedAt is null)
               and not exists (select o.id from WechatMessageOutbox o where o.familyId = :familyId
                 and o.conversationId = c.conversationId and o.dedupeKey = concat(c.idempotencyKey, ':result')
                 and o.status = com.familyapp.wechat.conversation.OutboxStatus.SENT)
             order by c.id desc
            """)
    List<WechatTaskConfirmation> undeliveredResults(@Param("familyId") Long familyId,
            @Param("userId") Long userId, @Param("bindId") Long bindId, Pageable page);

    Optional<WechatTaskConfirmation> findByIdAndFamilyId(Long id, Long familyId);

    Optional<WechatTaskConfirmation> findByCandidateVersionIdAndFamilyId(
            Long candidateVersionId, Long familyId);

    Optional<WechatTaskConfirmation> findByIdempotencyKeyAndFamilyId(
            String idempotencyKey, Long familyId);

    Optional<WechatTaskConfirmation> findByConversationIdAndFamilyIdAndCandidateRevision(
            Long conversationId, Long familyId, Integer candidateRevision);
}

