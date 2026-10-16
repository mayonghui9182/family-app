package com.familyapp.wechat.conversation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 微信会话消息仓储；对象读取必须按家庭隔离。 */
public interface WechatConversationMessageRepository extends Repository<WechatConversationMessage, Long> {

    <S extends WechatConversationMessage> S save(S entity);

    @Query("select coalesce(max(m.id),0) from WechatConversationMessage m where m.familyId=:familyId and m.conversationId=:conversationId")
    long latestId(@Param("familyId") Long familyId, @Param("conversationId") Long conversationId);

    @Query("""
            select m from WechatConversationMessage m where m.familyId=:familyId
              and m.conversationId=:conversationId and m.id > :afterId and m.receivedAt >= :cutoff
              order by m.id
            """)
    List<WechatConversationMessage> summaryRange(@Param("familyId") Long familyId,
            @Param("conversationId") Long conversationId, @Param("afterId") long afterId,
            @Param("cutoff") Instant cutoff, Pageable page);

    /** 最近八轮按数据库游标稳定排序，避免相同时间戳丢失本轮消息。 */
    List<WechatConversationMessage> findTop16ByFamilyIdAndConversationIdOrderByIdDesc(Long familyId, Long conversationId);

    Optional<WechatConversationMessage> findByIdAndFamilyId(Long id, Long familyId);

    /** 家庭尚未确定时，只允许在同一微信绑定作用域内读取消息。 */
    Optional<WechatConversationMessage> findByIdAndWechatBindId(Long id, Long wechatBindId);

    /**
     * 入站持久化发生在家庭路由之前，只允许用公众号与外部消息 ID 做全局幂等判断。
     */
    Optional<WechatConversationMessage> findByWechatAccountIdAndExternalMsgId(
            String wechatAccountId, String externalMsgId);

    /**
     * AI 动作回复幂等检查必须同时携带家庭和会话作用域。
     */
    Optional<WechatConversationMessage>
            findByWechatAccountIdAndExternalMsgIdAndWechatBindIdAndFamilyIdAndConversationId(
                    String wechatAccountId,
                    String externalMsgId,
                    Long wechatBindId,
                    Long familyId,
                    Long conversationId);

    List<WechatConversationMessage> findTop20ByFamilyIdAndConversationIdOrderByCreatedAtDesc(
            Long familyId, Long conversationId);

    /** 按最早可处理消息排列绑定，供 worker 小批量轮询。 */
    @Query("""
            select m.wechatBindId
              from WechatConversationMessage m
             where m.wechatBindId is not null
               and m.direction = com.familyapp.wechat.conversation.MessageDirection.INBOUND
               and m.attempts < :maxAttempts
               and ((m.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PENDING
                     and m.availableAt <= :now
                     and (m.leaseUntil is null or m.leaseUntil <= :now))
                    or (m.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PROCESSING
                        and m.leaseUntil <= :now))
               and not exists (
                   select earlier.id
                     from WechatConversationMessage earlier
                    where earlier.wechatBindId = m.wechatBindId
                      and earlier.direction = com.familyapp.wechat.conversation.MessageDirection.INBOUND
                      and (earlier.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PENDING
                           or earlier.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PROCESSING)
                      and (earlier.receivedAt < m.receivedAt
                           or (earlier.receivedAt = m.receivedAt and earlier.id < m.id))
               )
             group by m.wechatBindId
             order by min(m.receivedAt) asc, m.wechatBindId asc
            """)
    List<Long> findProcessableWechatBindIds(@Param("now") Instant now,
                                            @Param("maxAttempts") int maxAttempts,
                                            Pageable pageable);

    /** 同一绑定只选择接收时间最早的可处理入站消息。 */
    @Query("""
            select m
              from WechatConversationMessage m
             where m.wechatBindId = :wechatBindId
               and m.direction = com.familyapp.wechat.conversation.MessageDirection.INBOUND
               and m.attempts < :maxAttempts
               and ((m.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PENDING
                     and m.availableAt <= :now
                     and (m.leaseUntil is null or m.leaseUntil <= :now))
                    or (m.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PROCESSING
                        and m.leaseUntil <= :now))
               and not exists (
                   select earlier.id
                     from WechatConversationMessage earlier
                    where earlier.wechatBindId = m.wechatBindId
                      and earlier.direction = com.familyapp.wechat.conversation.MessageDirection.INBOUND
                      and (earlier.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PENDING
                           or earlier.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PROCESSING)
                      and (earlier.receivedAt < m.receivedAt
                           or (earlier.receivedAt = m.receivedAt and earlier.id < m.id))
               )
             order by m.receivedAt asc, m.id asc
            """)
    List<WechatConversationMessage> findProcessableForBind(
            @Param("wechatBindId") Long wechatBindId,
            @Param("now") Instant now,
            @Param("maxAttempts") int maxAttempts,
            Pageable pageable);

    /** 领取待处理消息，或在租约过期后接管崩溃 worker 留下的处理中消息。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WechatConversationMessage m
               set m.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PROCESSING,
                   m.attempts = m.attempts + 1,
                   m.leaseOwner = :workerId,
                   m.leaseUntil = :leaseUntil,
                   m.updatedAt = :now
             where m.id = :messageId
               and m.wechatBindId = :wechatBindId
               and m.direction = com.familyapp.wechat.conversation.MessageDirection.INBOUND
               and m.attempts < :maxAttempts
               and ((m.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PENDING
                     and m.availableAt <= :now
                     and (m.leaseUntil is null or m.leaseUntil <= :now))
                    or (m.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PROCESSING
                        and m.leaseUntil <= :now))
            """)
    int claimForProcessing(@Param("messageId") Long messageId,
                           @Param("wechatBindId") Long wechatBindId,
                           @Param("workerId") String workerId,
                           @Param("now") Instant now,
                           @Param("leaseUntil") Instant leaseUntil,
                           @Param("maxAttempts") int maxAttempts);

    /** 只有仍持有且尚未过期的消息 owner 才能续租。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WechatConversationMessage m
               set m.leaseUntil = :leaseUntil,
                   m.updatedAt = :now
             where m.id = :messageId
               and m.wechatBindId = :wechatBindId
               and m.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PROCESSING
               and m.leaseOwner = :workerId
               and m.leaseUntil > :now
            """)
    int renewProcessingLease(@Param("messageId") Long messageId,
                             @Param("wechatBindId") Long wechatBindId,
                             @Param("workerId") String workerId,
                             @Param("now") Instant now,
                             @Param("leaseUntil") Instant leaseUntil);

    /** 只有当前消息租约 owner 可以把本地命令处理结果提交为完成。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WechatConversationMessage m
               set m.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PROCESSED,
                   m.leaseOwner = null,
                   m.leaseUntil = null,
                   m.lastError = null,
                   m.processedAt = :now,
                   m.updatedAt = :now
             where m.id = :messageId
               and m.wechatBindId = :wechatBindId
               and m.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PROCESSING
               and m.leaseOwner = :workerId
            """)
    int completeProcessing(@Param("messageId") Long messageId,
                           @Param("wechatBindId") Long wechatBindId,
                           @Param("workerId") String workerId,
                           @Param("now") Instant now);

    /** 当前 owner 在未耗尽次数时，把失败消息退回带退避时间的待处理状态。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WechatConversationMessage m
               set m.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PENDING,
                   m.availableAt = :availableAt,
                   m.leaseOwner = null,
                   m.leaseUntil = null,
                   m.lastError = :reason,
                   m.processedAt = null,
                   m.updatedAt = :now
             where m.id = :messageId
               and m.wechatBindId = :wechatBindId
               and m.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PROCESSING
               and m.leaseOwner = :workerId
               and m.attempts < :maxAttempts
            """)
    int retryProcessing(@Param("messageId") Long messageId,
                        @Param("wechatBindId") Long wechatBindId,
                        @Param("workerId") String workerId,
                        @Param("reason") String reason,
                        @Param("now") Instant now,
                        @Param("availableAt") Instant availableAt,
                        @Param("maxAttempts") int maxAttempts);

    /** 当前 owner 在达到最大次数时，把消息收敛到可诊断的失败终态。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WechatConversationMessage m
               set m.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.FAILED,
                   m.leaseOwner = null,
                   m.leaseUntil = null,
                   m.lastError = :reason,
                   m.processedAt = :now,
                   m.updatedAt = :now
             where m.id = :messageId
               and m.wechatBindId = :wechatBindId
               and m.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PROCESSING
               and m.leaseOwner = :workerId
               and m.attempts >= :maxAttempts
            """)
    int failProcessing(@Param("messageId") Long messageId,
                       @Param("wechatBindId") Long wechatBindId,
                       @Param("workerId") String workerId,
                       @Param("reason") String reason,
                       @Param("now") Instant now,
                       @Param("maxAttempts") int maxAttempts);

    /** worker 崩溃且最后一次租约已过期时，主动收敛到 FAILED，避免永久堵塞队列。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WechatConversationMessage m
               set m.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.FAILED,
                   m.leaseOwner = null,
                   m.leaseUntil = null,
                   m.lastError = :reason,
                   m.processedAt = :now,
                   m.updatedAt = :now
             where m.direction = com.familyapp.wechat.conversation.MessageDirection.INBOUND
               and m.attempts >= :maxAttempts
               and (m.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PENDING
                    or (m.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PROCESSING
                        and m.leaseUntil <= :now))
            """)
    int failExpiredExhausted(@Param("now") Instant now,
                             @Param("maxAttempts") int maxAttempts,
                             @Param("reason") String reason);

    /** 陈旧 AI 响应只允许由当前 owner 标记跳过，不能覆盖其他 worker 的接管结果。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WechatConversationMessage m
               set m.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.SKIPPED,
                   m.leaseOwner = null,
                   m.leaseUntil = null,
                   m.lastError = :reason,
                   m.processedAt = :now,
                   m.updatedAt = :now
             where m.id = :messageId
               and m.wechatBindId = :wechatBindId
               and m.processingStatus = com.familyapp.wechat.conversation.MessageProcessingStatus.PROCESSING
               and m.leaseOwner = :workerId
            """)
    int skipStaleProcessing(@Param("messageId") Long messageId,
                            @Param("wechatBindId") Long wechatBindId,
                            @Param("workerId") String workerId,
                            @Param("reason") String reason,
                            @Param("now") Instant now);

}
