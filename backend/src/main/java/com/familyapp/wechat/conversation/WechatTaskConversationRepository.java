package com.familyapp.wechat.conversation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 所有对象读取均显式携带 familyId，禁止裸 id 跨家庭查询。 */
public interface WechatTaskConversationRepository extends Repository<WechatTaskConversation, Long> {

    <S extends WechatTaskConversation> S save(S entity);

    @Query("""
            select c from WechatTaskConversation c where c.familyId=:familyId and c.deletedAt is null
              and exists (select m.id from WechatConversationMessage m where m.familyId=:familyId
                and m.conversationId=c.id and (m.id > coalesce(c.summaryThroughMessageId,0)
                  or (c.summaryThroughMessageId is not null and not exists (
                    select covered.id from WechatConversationMessage covered where covered.familyId=:familyId
                      and covered.conversationId=c.id and covered.id=c.summaryThroughMessageId))))
              order by c.lastActivityAt asc, c.id asc
            """)
    List<WechatTaskConversation> summaryCandidates(@Param("familyId") Long familyId,
            org.springframework.data.domain.Pageable page);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically=true, flushAutomatically=true)
    @Query("""
            update WechatTaskConversation c set c.rollingSummary=:summary, c.summaryThroughMessageId=:throughId,
              c.version=c.version+1, c.updatedAt=:now
            where c.id=:id and c.familyId=:familyId and c.deletedAt is null and c.version=:version
              and ((c.summaryThroughMessageId is null and :previousId is null) or c.summaryThroughMessageId=:previousId)
              and :latestId = (select coalesce(max(m.id),0) from WechatConversationMessage m
                where m.familyId=:familyId and m.conversationId=:id)
            """)
    int commitSummary(@Param("id") Long id, @Param("familyId") Long familyId, @Param("version") Long version,
            @Param("previousId") Long previousId, @Param("summary") String summary,
            @Param("throughId") Long throughId, @Param("latestId") long latestId, @Param("now") java.time.Instant now);

    /** 终态释放 active_slot 后立即刷库，避免同事务新建会话撞唯一键。 */
    <S extends WechatTaskConversation> S saveAndFlush(S entity);

    Optional<WechatTaskConversation> findByIdAndFamilyId(Long id, Long familyId);

    /** 保留清理也必须锁定隐藏会话；仅维护服务可使用，不用于历史读取。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from WechatTaskConversation c where c.id=:id and c.familyId=:familyId")
    Optional<WechatTaskConversation> findForRetention(@Param("id") Long id, @Param("familyId") Long familyId);

    /** 仅用于确认结果重放；按创建顺序读取，避免越过较新的取消会话猜测旧确认。 */
    Optional<WechatTaskConversation> findFirstByWechatBindIdAndFamilyIdAndDeletedAtIsNullOrderByIdDesc(
            Long wechatBindId, Long familyId);

    /** AI 动作落库时按会话与家庭双重作用域加锁。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT c
              FROM WechatTaskConversation c
             WHERE c.id = :conversationId
               AND c.familyId = :familyId
               AND c.deletedAt IS NULL
            """)
    Optional<WechatTaskConversation> findByIdAndFamilyIdForUpdate(
            @Param("conversationId") Long conversationId,
            @Param("familyId") Long familyId);

    Optional<WechatTaskConversation> findByWechatBindIdAndFamilyIdAndActiveSlot(
            Long wechatBindId, Long familyId, Short activeSlot);

    /** 状态迁移必须在绑定与家庭双重作用域内串行化。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT c
              FROM WechatTaskConversation c
             WHERE c.wechatBindId = :wechatBindId
               AND c.familyId = :familyId
               AND c.activeSlot = 1
               AND c.deletedAt IS NULL
            """)
    Optional<WechatTaskConversation> findActiveForUpdate(
            @Param("wechatBindId") Long wechatBindId,
            @Param("familyId") Long familyId);

    List<WechatTaskConversation> findTop5ByWechatBindIdAndFamilyIdAndDeletedAtIsNullOrderByLastActivityAtDesc(
            Long wechatBindId, Long familyId);

    @Query("""
            select c from WechatTaskConversation c where c.familyId = :familyId
              and c.wechatBindId = :bindId and c.userId = :userId and c.deletedAt is null
              and c.status in (com.familyapp.wechat.conversation.ConversationStatus.PAUSED,
                com.familyapp.wechat.conversation.ConversationStatus.CONFIRMED,
                com.familyapp.wechat.conversation.ConversationStatus.CANCELLED,
                com.familyapp.wechat.conversation.ConversationStatus.EXPIRED)
              order by c.lastActivityAt desc, c.id desc
            """)
    List<WechatTaskConversation> history(@Param("familyId") Long familyId,
            @Param("bindId") Long bindId, @Param("userId") Long userId,
            org.springframework.data.domain.Pageable page);
}
