package com.familyapp.wechat.conversation;

import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnRequest;
import com.familyapp.wechat.conversation.WechatConversationLeaseService.MessageLease;
import com.familyapp.wechat.conversation.WechatConversationTurnPreparationService.PreparedTurn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * AI 外呼前后状态栅栏。
 *
 * <p>调用方必须已开启动作提交事务。本服务先锁定绑定路由，再锁定家庭会话，
 * 只有调用前快照与当前状态完全一致时才允许写入动作。</p>
 */
@Service
public class WechatConversationAiResponseFence {

    static final String STALE_REASON_CODE = "STALE_AI_RESPONSE";

    private final WechatChatContextRepository contextRepository;
    private final WechatTaskConversationRepository conversationRepository;
    private final WechatConversationMessageRepository messageRepository;

    public WechatConversationAiResponseFence(
            WechatChatContextRepository contextRepository,
            WechatTaskConversationRepository conversationRepository,
            WechatConversationMessageRepository messageRepository) {
        this.contextRepository = contextRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public FenceResult lockAndCheck(
            MessageLease lease,
            PreparedTurn preparedTurn,
            WechatConversationMessage source,
            Instant now) {
        if (lease == null
                || preparedTurn == null
                || source == null
                || now == null
                || !preparedTurn.requiresAi()
                || preparedTurn.conversationStatus() == null) {
            throw new IllegalArgumentException("AI 响应栅栏输入不完整");
        }
        TurnRequest request = preparedTurn.turnRequest();
        if (!Objects.equals(request.messageId(), source.getId())
                || request.routeRevision() == null
                || request.currentRevision() == null
                || !Objects.equals(request.conversationId(), preparedTurn.conversationId())) {
            throw new IllegalArgumentException("AI 响应栅栏快照不匹配");
        }

        WechatChatContext context = contextRepository
                .findByWechatBindIdForUpdate(lease.wechatBindId())
                .orElseThrow(() -> new IllegalStateException("AI 响应绑定上下文不存在"));
        requireOwnedContext(context, lease, preparedTurn, now);

        if (!Objects.equals(context.getRouteRevision(), request.routeRevision())
                || !Objects.equals(context.getCurrentFamilyId(), preparedTurn.familyId())) {
            return discard(lease, now, StaleReason.ROUTE_CHANGED);
        }

        Optional<WechatTaskConversation> locked =
                conversationRepository.findByIdAndFamilyIdForUpdate(
                        preparedTurn.conversationId(), preparedTurn.familyId());
        if (locked.isEmpty()) {
            return discard(lease, now, StaleReason.CONVERSATION_MISSING);
        }

        WechatTaskConversation conversation = locked.get();
        requireConversationScope(conversation, preparedTurn);
        if (!Objects.equals(
                conversation.getCurrentRevision(), request.currentRevision())) {
            return discard(lease, now, StaleReason.REVISION_CHANGED);
        }
        if (conversation.getStatus() != preparedTurn.conversationStatus()) {
            return discard(lease, now, StaleReason.STATUS_CHANGED);
        }
        return FenceResult.current(conversation);
    }

    private void requireOwnedContext(
            WechatChatContext context,
            MessageLease lease,
            PreparedTurn preparedTurn,
            Instant now) {
        if (!Objects.equals(context.getWechatBindId(), lease.wechatBindId())
                || !Objects.equals(context.getUserId(), preparedTurn.userId())) {
            throw new IllegalStateException("AI 响应绑定上下文作用域不匹配");
        }
        if (!Objects.equals(context.getLeaseOwner(), lease.workerId())
                || context.getLeaseUntil() == null
                || !context.getLeaseUntil().isAfter(now)) {
            throw new IllegalStateException("AI 响应绑定租约已失效");
        }
    }

    private void requireConversationScope(
            WechatTaskConversation conversation,
            PreparedTurn preparedTurn) {
        if (!Objects.equals(conversation.getWechatBindId(),
                preparedTurn.lease().wechatBindId())
                || !Objects.equals(conversation.getUserId(), preparedTurn.userId())
                || !Objects.equals(conversation.getFamilyId(), preparedTurn.familyId())) {
            throw new IllegalStateException("AI 响应目标会话作用域不匹配");
        }
    }

    private FenceResult discard(
            MessageLease lease,
            Instant now,
            StaleReason reason) {
        int skipped = messageRepository.skipStaleProcessing(
                lease.messageId(),
                lease.wechatBindId(),
                lease.workerId(),
                STALE_REASON_CODE + ":" + reason.name(),
                now);
        if (skipped == 0) {
            throw new IllegalStateException("陈旧 AI 响应来源消息跳过冲突");
        }
        if (contextRepository.releaseLease(
                lease.wechatBindId(), lease.workerId(), now) == 0) {
            throw new IllegalStateException("陈旧 AI 响应绑定租约释放失败");
        }
        return FenceResult.stale(reason);
    }

    public enum StaleReason {
        ROUTE_CHANGED,
        CONVERSATION_MISSING,
        REVISION_CHANGED,
        STATUS_CHANGED
    }

    public record FenceResult(
            boolean current,
            WechatTaskConversation conversation,
            StaleReason staleReason) {

        static FenceResult current(WechatTaskConversation conversation) {
            return new FenceResult(true, conversation, null);
        }

        static FenceResult stale(StaleReason reason) {
            return new FenceResult(false, null, reason);
        }
    }
}
