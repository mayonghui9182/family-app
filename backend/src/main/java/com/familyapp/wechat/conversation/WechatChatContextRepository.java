package com.familyapp.wechat.conversation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * 微信绑定级路由上下文仓储。
 *
 * <p>该对象先于家庭路由存在，因此按 wechatBindId 作用域，而不是伪造 familyId 条件。
 */
public interface WechatChatContextRepository extends Repository<WechatChatContext, Long> {

    <S extends WechatChatContext> S save(S entity);

    Optional<WechatChatContext> findByWechatBindId(Long wechatBindId);

    /** 家庭选择和 route_revision 迁移必须串行化。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM WechatChatContext c WHERE c.wechatBindId = :wechatBindId")
    Optional<WechatChatContext> findByWechatBindIdForUpdate(
            @Param("wechatBindId") Long wechatBindId);

    boolean existsByWechatBindId(Long wechatBindId);

    /** 家庭删除维护只枚举可能引用目标家庭的绑定路由，不读取其他家庭业务对象。 */
    @Query("""
            select c from WechatChatContext c where c.currentFamilyId=:familyId or c.defaultFamilyId=:familyId
              or c.pendingFamilySelection is not null or c.pendingHistoryDelete is not null
              or exists (select v.id from WechatTaskConversation v
                         where v.familyId=:familyId and v.wechatBindId=c.wechatBindId)
            order by c.wechatBindId
            """)
    java.util.List<WechatChatContext> deletionRouteCandidates(@Param("familyId") Long familyId);

    /** 仅空闲或已过期的绑定租约可被一个 worker 原子抢占。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WechatChatContext c
               set c.leaseOwner = :workerId,
                   c.leaseUntil = :leaseUntil,
                   c.updatedAt = :now
             where c.wechatBindId = :wechatBindId
               and (c.leaseUntil is null or c.leaseUntil <= :now)
            """)
    int claimExpiredLease(@Param("wechatBindId") Long wechatBindId,
                          @Param("workerId") String workerId,
                          @Param("now") Instant now,
                          @Param("leaseUntil") Instant leaseUntil);

    /** 只有仍持有且尚未过期的 owner 才能续租，避免过期 worker 抢回所有权。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WechatChatContext c
               set c.leaseUntil = :leaseUntil,
                   c.updatedAt = :now
             where c.wechatBindId = :wechatBindId
               and c.leaseOwner = :workerId
               and c.leaseUntil > :now
            """)
    int renewLease(@Param("wechatBindId") Long wechatBindId,
                   @Param("workerId") String workerId,
                   @Param("now") Instant now,
                   @Param("leaseUntil") Instant leaseUntil);

    /** owner 匹配时释放绑定租约；其他 worker 不能误释放。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WechatChatContext c
               set c.leaseOwner = null,
                   c.leaseUntil = null,
                   c.updatedAt = :now
             where c.wechatBindId = :wechatBindId
               and c.leaseOwner = :workerId
            """)
    int releaseLease(@Param("wechatBindId") Long wechatBindId,
                     @Param("workerId") String workerId,
                     @Param("now") Instant now);
}
