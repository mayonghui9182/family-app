package com.familyapp.wechat.conversation;

import com.familyapp.wechat.WechatBind;
import com.familyapp.wechat.WechatBindRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 微信会话 worker 的短事务租约协调器。
 *
 * <p>绑定租约保证同一微信用户串行处理，消息租约则让崩溃后的处理中消息能够被安全接管。</p>
 */
@Service
public class WechatConversationLeaseService {

    private static final String EXHAUSTED_REASON =
            "LEASE_EXPIRED_ATTEMPTS_EXHAUSTED";

    private final WechatChatContextRepository contextRepository;
    private final WechatConversationMessageRepository messageRepository;
    private final WechatBindRepository bindRepository;
    private final WechatConversationProperties properties;
    private final TransactionTemplate transactionTemplate;

    public WechatConversationLeaseService(WechatChatContextRepository contextRepository,
                                          WechatConversationMessageRepository messageRepository,
                                          WechatBindRepository bindRepository,
                                          WechatConversationProperties properties,
                                          TransactionTemplate transactionTemplate) {
        this.contextRepository = contextRepository;
        this.messageRepository = messageRepository;
        this.bindRepository = bindRepository;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    /** 使用配置的租约时长领取指定绑定下最早的可处理消息。 */
    public Optional<MessageLease> claimNext(long wechatBindId, String workerId, Instant now) {
        return claimNext(
                wechatBindId,
                workerId,
                now,
                Duration.ofSeconds(properties.getLeaseSeconds()),
                properties.getMaxAttempts());
    }

    /**
     * 先领取绑定租约，再领取最早消息；整个过程只有数据库操作且位于同一个短事务。
     */
    Optional<MessageLease> claimNext(long wechatBindId,
                                     String workerId,
                                     Instant now,
                                     Duration leaseDuration,
                                     int maxAttempts) {
        validateLeaseInput(wechatBindId, workerId, now, leaseDuration);
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 必须大于零");
        }
        Instant leaseUntil = now.plus(leaseDuration);

        Optional<MessageLease> result = transactionTemplate.execute(status -> {
            finalizeExhaustedMessages(now);
            if (!ensureContext(wechatBindId)) {
                return Optional.empty();
            }
            int contextClaimed = contextRepository.claimExpiredLease(
                    wechatBindId, workerId, now, leaseUntil);
            if (contextClaimed == 0) {
                return Optional.empty();
            }

            List<WechatConversationMessage> candidates = messageRepository.findProcessableForBind(
                    wechatBindId, now, maxAttempts, PageRequest.of(0, 1));
            if (candidates.isEmpty()) {
                contextRepository.releaseLease(wechatBindId, workerId, now);
                return Optional.empty();
            }

            WechatConversationMessage candidate = candidates.get(0);
            int messageClaimed = messageRepository.claimForProcessing(
                    candidate.getId(),
                    wechatBindId,
                    workerId,
                    now,
                    leaseUntil,
                    maxAttempts);
            if (messageClaimed == 0) {
                contextRepository.releaseLease(wechatBindId, workerId, now);
                return Optional.empty();
            }

            WechatConversationMessage claimed = messageRepository
                    .findByIdAndWechatBindId(candidate.getId(), wechatBindId)
                    .orElseThrow(() -> new IllegalStateException("已领取的微信消息不存在"));
            return Optional.of(new MessageLease(
                    claimed.getId(),
                    wechatBindId,
                    workerId,
                    leaseUntil,
                    claimed.getAttempts()));
        });
        return result == null ? Optional.empty() : result;
    }

    /** 查询目前存在可处理消息的绑定，顺序与消息接收顺序一致。 */
    public List<Long> findProcessableWechatBindIds(Instant now, int batchSize) {
        Objects.requireNonNull(now, "now 不能为空");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize 必须大于零");
        }
        List<Long> result = transactionTemplate.execute(status -> {
            finalizeExhaustedMessages(now);
            return messageRepository.findProcessableWechatBindIds(
                    now,
                    properties.getMaxAttempts(),
                    PageRequest.of(0, batchSize));
        });
        return result == null ? List.of() : result;
    }

    /** 使用配置的租约时长同时续租绑定和消息。 */
    public boolean renew(MessageLease lease, Instant now) {
        return renew(lease, now, Duration.ofSeconds(properties.getLeaseSeconds()));
    }

    /**
     * 两级租约必须一起续租；消息续租失败时回滚绑定续租，避免出现半成功状态。
     */
    boolean renew(MessageLease lease, Instant now, Duration leaseDuration) {
        Objects.requireNonNull(lease, "lease 不能为空");
        validateLeaseInput(
                lease.wechatBindId(), lease.workerId(), now, leaseDuration);
        Instant leaseUntil = now.plus(leaseDuration);

        Boolean renewed = transactionTemplate.execute(status -> {
            int contextRenewed = contextRepository.renewLease(
                    lease.wechatBindId(), lease.workerId(), now, leaseUntil);
            if (contextRenewed == 0) {
                return false;
            }
            int messageRenewed = messageRepository.renewProcessingLease(
                    lease.messageId(),
                    lease.wechatBindId(),
                    lease.workerId(),
                    now,
                    leaseUntil);
            if (messageRenewed == 0) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        return Boolean.TRUE.equals(renewed);
    }

    /** 仅当前 owner 可以释放绑定租约；消息状态由后续处理流程单独收敛。 */
    public boolean releaseBindingLease(long wechatBindId, String workerId, Instant now) {
        validateWorker(wechatBindId, workerId);
        Objects.requireNonNull(now, "now 不能为空");
        Integer released = transactionTemplate.execute(status ->
                contextRepository.releaseLease(wechatBindId, workerId, now));
        return released != null && released == 1;
    }


    /** 把租约过期且已耗尽次数的消息收敛为失败终态，解除同一绑定队列阻塞。 */
    private void finalizeExhaustedMessages(Instant now) {
        messageRepository.failExpiredExhausted(
                now, properties.getMaxAttempts(), EXHAUSTED_REASON);
    }
    /** 首条消息到达时在绑定行锁保护下补建串行上下文。 */
    private boolean ensureContext(long wechatBindId) {
        if (contextRepository.existsByWechatBindId(wechatBindId)) {
            return true;
        }
        Optional<WechatBind> lockedBind = bindRepository.findByIdForUpdate(wechatBindId);
        if (lockedBind.isEmpty()) {
            return false;
        }
        if (!contextRepository.existsByWechatBindId(wechatBindId)) {
            WechatChatContext context = new WechatChatContext();
            context.setWechatBindId(wechatBindId);
            context.setUserId(lockedBind.get().getUser().getId());
            contextRepository.save(context);
        }
        return true;
    }

    private void validateLeaseInput(long wechatBindId,
                                    String workerId,
                                    Instant now,
                                    Duration leaseDuration) {
        validateWorker(wechatBindId, workerId);
        Objects.requireNonNull(now, "now 不能为空");
        Objects.requireNonNull(leaseDuration, "leaseDuration 不能为空");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration 必须大于零");
        }
    }

    private void validateWorker(long wechatBindId, String workerId) {
        if (wechatBindId < 1) {
            throw new IllegalArgumentException("wechatBindId 必须大于零");
        }
        if (workerId == null || workerId.isBlank() || workerId.length() > 100) {
            throw new IllegalArgumentException("workerId 必须为 1 到 100 个字符");
        }
    }

    /** 成功领取后的不可变租约快照。 */
    public record MessageLease(
            long messageId,
            long wechatBindId,
            String workerId,
            Instant leaseUntil,
            int attempts) {
    }
}
