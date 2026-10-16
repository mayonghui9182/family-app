package com.familyapp.wechat.conversation;

import com.familyapp.wechat.conversation.WechatConversationLeaseService.MessageLease;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** 用短事务把单条微信会话消息收敛到退避重试或失败终态。 */
@Service
public class WechatConversationFailureService {

    private static final Pattern REASON_PATTERN =
            Pattern.compile("[A-Z][A-Z0-9_]{0,99}");

    private final WechatConversationMessageRepository messageRepository;
    private final WechatChatContextRepository contextRepository;
    private final WechatConversationProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final WechatConversationOutboxService outbox;

    public WechatConversationFailureService(
            WechatConversationMessageRepository messageRepository,
            WechatChatContextRepository contextRepository,
            WechatConversationProperties properties,
            TransactionTemplate transactionTemplate,
            WechatConversationOutboxService outbox) {
        this.messageRepository = messageRepository;
        this.contextRepository = contextRepository;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.outbox = outbox;
    }

    /**
     * 失败正文不会写入数据库；只保存稳定错误码，并与绑定租约释放一起原子提交。
     */
    public FailureRecord recordFailure(MessageLease lease, String reason, Instant now) {
        Objects.requireNonNull(lease, "lease 不能为空");
        Objects.requireNonNull(now, "now 不能为空");
        if (reason == null || !REASON_PATTERN.matcher(reason).matches()) {
            throw new IllegalArgumentException("reason 必须是稳定的非敏感错误码");
        }

        FailureRecord result = transactionTemplate.execute(status -> {
            int maxAttempts = properties.getMaxAttempts();
            boolean deterministicFailure = "AI_INVALID_REQUEST".equals(reason)
                    || "AI_INVALID_RESPONSE".equals(reason);
            boolean terminal = lease.attempts() >= maxAttempts || deterministicFailure;
            // 与路由切换使用相同的绑定锁；通知和失败状态在同一事务提交。
            WechatChatContext context = terminal
                    ? contextRepository.findByWechatBindIdForUpdate(lease.wechatBindId()).orElse(null)
                    : null;
            FailureRecord record;
            int messageUpdated;
            if (terminal) {
                messageUpdated = messageRepository.failProcessing(
                        lease.messageId(),
                        lease.wechatBindId(),
                        lease.workerId(),
                        reason,
                        now,
                        deterministicFailure ? 1 : maxAttempts);
                record = new FailureRecord(FailureOutcome.FAILED, null, reason);
            } else {
                Instant availableAt = now.plusMillis(
                        backoffDelayMillis(lease.attempts()));
                messageUpdated = messageRepository.retryProcessing(
                        lease.messageId(),
                        lease.wechatBindId(),
                        lease.workerId(),
                        reason,
                        now,
                        availableAt,
                        maxAttempts);
                record = new FailureRecord(
                        FailureOutcome.RETRY_SCHEDULED, availableAt, reason);
            }
            if (messageUpdated != 1) {
                throw new IllegalStateException("微信会话消息失败状态提交冲突");
            }

            if (terminal && context != null) {
                enqueueFailureReply(lease, context, now);
            }

            int contextReleased = contextRepository.releaseLease(
                    lease.wechatBindId(), lease.workerId(), now);
            if (contextReleased != 1) {
                throw new IllegalStateException("微信会话绑定租约释放冲突");
            }
            return record;
        });
        if (result == null) {
            throw new IllegalStateException("微信会话失败状态事务未返回结果");
        }
        return result;
    }

    /** 已确认家庭的失败消息才进入出站队列；跨家庭切换后不回发旧家庭提示。 */
    private void enqueueFailureReply(MessageLease lease, WechatChatContext context, Instant now) {
        var source = messageRepository.findByIdAndWechatBindId(
                lease.messageId(), lease.wechatBindId()).orElseThrow();
        Long familyId = source.getFamilyId();
        if (familyId == null || source.getConversationId() == null
                || !Objects.equals(context.getCurrentFamilyId(), familyId)
                || !Objects.equals(context.getUserId(), source.getUserId())) {
            return;
        }
        var scoped = messageRepository.findByIdAndFamilyId(source.getId(), familyId)
                .orElseThrow(WechatFamilyAccessDeniedException::new);
        outbox.enqueueReply(scoped, familyId, source.getConversationId(),
                "failure:" + source.getId(),
                "这次整理没有完成，暂未生成待办。请稍后重试，或把长消息分段发送。",
                now);
    }

    /** 第一次失败使用初始延迟，随后倍增并在上限处截断。 */
    long backoffDelayMillis(int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts 必须大于零");
        }
        long maximum = properties.getRetryMaxDelayMs();
        long delay = Math.min(properties.getRetryInitialDelayMs(), maximum);
        for (int index = 1; index < attempts && delay < maximum; index++) {
            if (delay > maximum / 2) {
                return maximum;
            }
            delay *= 2;
        }
        return Math.min(delay, maximum);
    }

    public enum FailureOutcome {
        RETRY_SCHEDULED,
        FAILED
    }

    public record FailureRecord(
            FailureOutcome outcome,
            Instant availableAt,
            String reason) {
    }
}

