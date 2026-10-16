package com.familyapp.wechat.conversation;

import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import java.time.Instant;

import java.util.List;
import java.util.Optional;

/** 有序客服消息 outbox 仓储；业务查询必须携带 familyId。 */
public interface WechatMessageOutboxRepository extends Repository<WechatMessageOutbox, Long> {

    <S extends WechatMessageOutbox> S save(S entity);

    @Query("select coalesce(max(o.sequenceNo), 0) from WechatMessageOutbox o where o.familyId = :familyId and o.conversationId = :conversationId")
    long lastSequence(@Param("familyId") Long familyId, @Param("conversationId") Long conversationId);

    Optional<WechatMessageOutbox> findByIdAndFamilyId(Long id, Long familyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from WechatMessageOutbox o where o.id = :id and o.familyId = :familyId")
    Optional<WechatMessageOutbox> findForUpdate(@Param("id") Long id, @Param("familyId") Long familyId);

    @Query("""
            select o from WechatMessageOutbox o where o.familyId = :familyId
              and o.status in (com.familyapp.wechat.conversation.OutboxStatus.PENDING,
                               com.familyapp.wechat.conversation.OutboxStatus.SENDING)
              and o.notBefore <= :now and (o.nextRetryAt is null or o.nextRetryAt <= :now)
              and (o.leaseUntil is null or o.leaseUntil <= :now)
              and not exists (select earlier.id from WechatMessageOutbox earlier
                where earlier.familyId = :familyId and earlier.conversationId = o.conversationId
                  and earlier.sequenceNo < o.sequenceNo
                  and earlier.status in (com.familyapp.wechat.conversation.OutboxStatus.PENDING,
                                         com.familyapp.wechat.conversation.OutboxStatus.SENDING))
              order by o.id
            """)
    List<WechatMessageOutbox> due(@Param("familyId") Long familyId, @Param("now") Instant now, Pageable page);

    @Query("""
            select count(o) from WechatMessageOutbox o where o.familyId = :familyId
              and o.conversationId = :conversationId and o.sequenceNo < :sequence
              and o.status in (com.familyapp.wechat.conversation.OutboxStatus.PENDING,
                               com.familyapp.wechat.conversation.OutboxStatus.SENDING)
            """)
    long earlierPending(@Param("familyId") Long familyId, @Param("conversationId") Long conversationId,
                        @Param("sequence") Long sequence);

    Optional<WechatMessageOutbox> findByConversationIdAndFamilyIdAndDedupeKey(
            Long conversationId, Long familyId, String dedupeKey);

    Optional<WechatMessageOutbox> findByConversationIdAndFamilyIdAndSequenceNo(
            Long conversationId, Long familyId, Long sequenceNo);

    List<WechatMessageOutbox> findByConversationIdAndFamilyIdOrderBySequenceNoAsc(
            Long conversationId, Long familyId);
}

