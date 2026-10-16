package com.familyapp.wechat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * wechat_inbound_message 仓储（Change 14）：幂等判定 + 队列捞取 + 状态机迁移。
 *
 * <p>状态迁移一律写成 CAS（compare-and-set）条件的批量 UPDATE，而非「读实体 → set → save」：
 * 前者由数据库保证「只有一个消费者能把 PENDING 翻成 RUNNING」，多实例部署也不会重复拆解；
 * 后者是两拍，两拍之间被别人抢走就会双跑。
 *
 * <p>这些方法都要求在事务内调用（作业用 TransactionTemplate 逐处包；{@link #attachSession}
 * 自带 {@code @Transactional}），每个迁移方法恰好对应一个独立小事务，绝不跨 LLM 外呼持有事务。
 */
public interface WechatInboundMessageRepository extends JpaRepository<WechatInboundMessage, Long> {

    /** 幂等短路：微信重推同一 msg_id 时不再落第二行。 */
    boolean existsByMsgId(String msgId);

    Optional<WechatInboundMessage> findByMsgId(String msgId);

    /** 轮询捞取待处理行，按入站时间先到先处理，单批上限 20 条。 */
    List<WechatInboundMessage> findTop20ByStatusOrderByReceivedAtAsc(WechatInboundStatus status);

    /**
     * 捞取可消费行：PENDING，以及 attempts 未达上限的遗留 RUNNING。
     *
     * <p>纳入 RUNNING 是为了崩溃自愈——进程在拆解中途被杀，行会永久停在 RUNNING；
     * 只要 attempts 还有余额就该被重新捞起，否则队列会悄悄积压且无任何报错。
     * 已达上限的 RUNNING 行不捞：那是人工排障对象，不该被无限重试。
     *
     * @param maxAttempts 重试上限，与 {@code wechat.intake.max-attempts} 一致
     */
    @Query("""
            select m from WechatInboundMessage m
            where m.status = com.familyapp.wechat.WechatInboundStatus.PENDING
               or (m.status = com.familyapp.wechat.WechatInboundStatus.RUNNING
                   and m.attempts < :maxAttempts)
            order by m.receivedAt asc
            """)
    List<WechatInboundMessage> findProcessable(Pageable pageable, @Param("maxAttempts") int maxAttempts);

    /**
     * CAS 抢占：把一行翻成 RUNNING 并把 attempts 自增 1。
     *
     * <p>WHERE 谓词与 {@link #findProcessable} 完全一致，返回 0 表示该行已被其他
     * 线程/实例抢走或已终态，调用方应直接放弃而不是重试。
     */
    @Modifying
    @Query("""
            update WechatInboundMessage m
            set m.status = com.familyapp.wechat.WechatInboundStatus.RUNNING,
                m.attempts = m.attempts + 1,
                m.updatedAt = :now
            where m.msgId = :msgId
              and (m.status = com.familyapp.wechat.WechatInboundStatus.PENDING
                   or (m.status = com.familyapp.wechat.WechatInboundStatus.RUNNING
                       and m.attempts < :maxAttempts))
            """)
    int claim(@Param("msgId") String msgId,
              @Param("maxAttempts") int maxAttempts,
              @Param("now") Instant now);

    /** 成功收尾：回写草稿指针并清空错误痕迹。 */
    @Modifying
    @Query("""
            update WechatInboundMessage m
            set m.status = com.familyapp.wechat.WechatInboundStatus.DONE,
                m.sessionId = :sessionId,
                m.lastError = null,
                m.updatedAt = :now
            where m.msgId = :msgId
            """)
    int markDone(@Param("msgId") String msgId,
                 @Param("sessionId") Long sessionId,
                 @Param("now") Instant now);

    /**
     * 中途挂草稿指针：只写 {@code session_id}，<b>不动 status</b>。
     *
     * <p>与 {@link #markDone} 的区别在时机：拆解失败回队时行仍是 PENDING，但草稿已经建好，
     * 必须在这一刻就把指针落库，下一轮才认得它、才不会重复建草稿
     * （一条消息长出两份草稿 = 用户收到两条推送 + 一份永远没人确认的空草稿）。
     *
     * <p>{@code @Transactional} 在这里是必需的，与同类其他迁移方法不同：那些都由作业用
     * {@code TransactionTemplate} 显式包着调，而本方法在拆解编排内部调用，编排外层没有事务
     * （D2「事务绝不跨外呼」）。缺了它，脱离事务执行 {@code @Modifying} 会抛
     * {@code TransactionRequiredException}。
     *
     * <p>{@code and m.sessionId is null} 是 CAS 谓词：并发下只让第一个把草稿挂上去的线程生效，
     * 不会覆盖已挂的草稿。
     */
    @Transactional
    @Modifying
    @Query("""
            update WechatInboundMessage m
            set m.sessionId = :sessionId,
                m.updatedAt = :now
            where m.msgId = :msgId
              and m.sessionId is null
            """)
    int attachSession(@Param("msgId") String msgId,
                      @Param("sessionId") Long sessionId,
                      @Param("now") Instant now);

    /** 失败但未耗尽：回 PENDING 等下一轮轮询（attempts 已在抢占时自增，此处不再累加）。 */
    @Modifying
    @Query("""
            update WechatInboundMessage m
            set m.status = com.familyapp.wechat.WechatInboundStatus.PENDING,
                m.lastError = :error,
                m.updatedAt = :now
            where m.msgId = :msgId
            """)
    int markRetry(@Param("msgId") String msgId,
                  @Param("error") String error,
                  @Param("now") Instant now);

    /** 重试耗尽：落 FAILED 终态，交由回推话术告知用户，不再自动处理。 */
    @Modifying
    @Query("""
            update WechatInboundMessage m
            set m.status = com.familyapp.wechat.WechatInboundStatus.FAILED,
                m.lastError = :error,
                m.updatedAt = :now
            where m.msgId = :msgId
            """)
    int markFailed(@Param("msgId") String msgId,
                   @Param("error") String error,
                   @Param("now") Instant now);

    /** 不该自动处理（如 AI 总开关关闭）：落 SKIPPED 并记录契约原因。 */
    @Modifying
    @Query("""
            update WechatInboundMessage m
            set m.status = com.familyapp.wechat.WechatInboundStatus.SKIPPED,
                m.skipReason = :reason,
                m.updatedAt = :now
            where m.msgId = :msgId
            """)
    int markSkipped(@Param("msgId") String msgId,
                    @Param("reason") String reason,
                    @Param("now") Instant now);
}
