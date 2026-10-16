package com.familyapp.wechat.conversation;

import com.familyapp.wechat.conversation.WechatFamilyRoutingService.FamilyOption;
import com.familyapp.wechat.conversation.WechatFamilyRoutingService.FamilySelectionChallenge;
import com.familyapp.wechat.conversation.WechatFamilyRoutingService.RouteDecision;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.familyapp.wechat.conversation.MessageProcessingStatus.PENDING;
import static com.familyapp.wechat.conversation.MessageProcessingStatus.PROCESSED;
import static com.familyapp.wechat.conversation.MessageProcessingStatus.WAITING_FAMILY;

/**
 * 微信消息家庭选择协调器。
 *
 * <p>家庭挑战与原消息编号保存在绑定级上下文中，并通过悲观锁串行化选择，
 * 确保选择回复只被消费一次，原始任务文本最多重新入队一次。</p>
 */
@Service
public class WechatFamilySelectionCoordinator {

    private static final String PENDING_MESSAGE_ID = "pending_message_id";
    private static final String WECHAT_BIND_ID = "wechat_bind_id";
    private static final String ROUTE_REVISION = "route_revision";
    private static final String EXPIRES_AT = "expires_at";
    private static final String CANDIDATE_FAMILY_IDS = "candidate_family_ids";

    private final WechatConversationMessageRepository messageRepository;
    private final WechatChatContextRepository contextRepository;
    private final WechatFamilyRoutingService routingService;
    private final Clock clock;

    public WechatFamilySelectionCoordinator(
            WechatConversationMessageRepository messageRepository,
            WechatChatContextRepository contextRepository,
            WechatFamilyRoutingService routingService,
            Clock clock) {
        this.messageRepository = messageRepository;
        this.contextRepository = contextRepository;
        this.routingService = routingService;
        this.clock = clock;
    }

    /**
     * 为一条已绑定但尚未确定家庭的消息建立路由。
     */
    @Transactional
    public RoutingAction routeMessage(long wechatBindId, long messageId) {
        WechatConversationMessage message = messageRepository
                .findByIdAndWechatBindId(messageId, wechatBindId)
                .orElseThrow(() -> new IllegalArgumentException("微信消息不存在或不属于当前绑定"));
        if (message.getFamilyId() != null
                || (message.getProcessingStatus() != PENDING
                && message.getProcessingStatus() != MessageProcessingStatus.PROCESSING)) {
            return RoutingAction.alreadyHandled();
        }

        WechatChatContext context = lockedContext(message);
        Instant now = clock.instant();
        RouteDecision decision = routingService.automaticSelection(wechatBindId,
                context.getCurrentFamilyId(), context.getDefaultFamilyId());
        if (decision.selected() == null
                || !decision.selected().familyId().equals(context.getCurrentFamilyId())) nextRevision(context);
        return switch (decision.outcome()) {
            case AUTO_SELECTED -> autoSelect(message, context, decision, now);
            case CHALLENGE -> waitForSelection(message, context, decision);
            case NO_FAMILY, NOT_BOUND -> waitWithoutCandidates(message, context);
            default -> throw new IllegalStateException(
                    "开始家庭选择时出现非法结果: " + decision.outcome());
        };
    }

    /**
     * 尝试把当前消息作为家庭选择回复消费。
     *
     * <p>返回 REPLAY 才表示原始消息可以继续后续 AI 流程；选择回复本身始终不会进入 AI。</p>
     */
    @Transactional
    public RoutingAction applySelection(
            long wechatBindId,
            long selectionMessageId,
            String answer) {
        WechatConversationMessage selectionMessage = messageRepository
                .findByIdAndWechatBindId(selectionMessageId, wechatBindId)
                .orElseThrow(() -> new IllegalArgumentException("选择消息不存在或不属于当前绑定"));
        Optional<WechatChatContext> locked =
                contextRepository.findByWechatBindIdForUpdate(wechatBindId);
        if (locked.isEmpty() || locked.get().getPendingFamilySelection() == null) {
            return RoutingAction.noChallenge();
        }

        WechatChatContext context = locked.get();
        PendingChallenge pending;
        try {
            pending = parsePending(context.getPendingFamilySelection());
        } catch (RuntimeException ex) {
            consumeRejectedSelection(selectionMessage, null, clock.instant());
            clearInvalidChallenge(context);
            return RoutingAction.rejected();
        }

        if (pending.wechatBindId() != wechatBindId
                || pending.routeRevision() != currentRevision(context)) {
            consumeRejectedSelection(selectionMessage, null, clock.instant());
            clearInvalidChallenge(context);
            return RoutingAction.rejected();
        }

        Instant now = clock.instant();
        FamilySelectionChallenge challenge = new FamilySelectionChallenge(
                pending.wechatBindId(),
                pending.routeRevision(),
                pending.expiresAt(),
                pending.candidateFamilyIds());
        RouteDecision decision = routingService.resolveSelection(
                wechatBindId,
                currentRevision(context),
                challenge,
                answer,
                now);

        return switch (decision.outcome()) {
            case SELECTED -> replayOriginal(
                    selectionMessage,
                    context,
                    pending,
                    decision.selected(),
                    now);
            case AMBIGUOUS -> narrowChallenge(
                    selectionMessage,
                    context,
                    pending.pendingMessageId(),
                    decision,
                    now);
            case EXPIRED, NOT_BOUND, NO_FAMILY -> rejectAndClear(
                    selectionMessage,
                    context,
                    now);
            case NO_MATCH -> rejectAndKeepChallenge(selectionMessage, now);
            default -> throw new IllegalStateException(
                    "解析家庭选择时出现非法结果: " + decision.outcome());
        };
    }

    private RoutingAction autoSelect(
            WechatConversationMessage message,
            WechatChatContext context,
            RouteDecision decision,
            Instant now) {
        Long familyId = decision.selected().familyId();
        context.setCurrentFamilyId(familyId);
        context.setPendingFamilySelection(null);
        contextRepository.save(context);

        message.setFamilyId(familyId);
        message.setAvailableAt(now);
        messageRepository.save(message);
        return RoutingAction.routed(message.getId(), familyId);
    }

    private RoutingAction waitForSelection(
            WechatConversationMessage message,
            WechatChatContext context,
            RouteDecision decision) {
        message.setProcessingStatus(WAITING_FAMILY);
        messageRepository.save(message);

        context.setPendingFamilySelection(toMap(message.getId(), decision.challenge()));
        contextRepository.save(context);
        return RoutingAction.challenge(decision.options());
    }

    private RoutingAction waitWithoutCandidates(
            WechatConversationMessage message,
            WechatChatContext context) {
        message.setProcessingStatus(WAITING_FAMILY);
        messageRepository.save(message);
        context.setPendingFamilySelection(null);
        contextRepository.save(context);
        return RoutingAction.noFamily();
    }

    private RoutingAction replayOriginal(
            WechatConversationMessage selectionMessage,
            WechatChatContext context,
            PendingChallenge pending,
            FamilyOption selected,
            Instant now) {
        WechatConversationMessage original = messageRepository
                .findByIdAndWechatBindId(pending.pendingMessageId(), pending.wechatBindId())
                .orElse(null);
        if (original == null
                || original.getFamilyId() != null
                || original.getProcessingStatus() != WAITING_FAMILY) {
            consumeRejectedSelection(selectionMessage, null, now);
            clearInvalidChallenge(context);
            return RoutingAction.rejected();
        }

        Long familyId = selected.familyId();
        original.setFamilyId(familyId);
        original.setProcessingStatus(PENDING);
        original.setAvailableAt(now);
        messageRepository.save(original);

        consumeRejectedSelection(selectionMessage, familyId, now);
        context.setCurrentFamilyId(familyId);
        context.setPendingFamilySelection(null);
        context.setRouteRevision(currentRevision(context) + 1);
        contextRepository.save(context);
        return RoutingAction.replay(original.getId(), familyId);
    }

    private RoutingAction narrowChallenge(
            WechatConversationMessage selectionMessage,
            WechatChatContext context,
            long pendingMessageId,
            RouteDecision decision,
            Instant now) {
        consumeRejectedSelection(selectionMessage, null, now);
        context.setPendingFamilySelection(toMap(pendingMessageId, decision.challenge()));
        contextRepository.save(context);
        return RoutingAction.challenge(decision.options());
    }

    private RoutingAction rejectAndClear(
            WechatConversationMessage selectionMessage,
            WechatChatContext context,
            Instant now) {
        consumeRejectedSelection(selectionMessage, null, now);
        clearInvalidChallenge(context);
        return RoutingAction.rejected();
    }

    private RoutingAction rejectAndKeepChallenge(
            WechatConversationMessage selectionMessage,
            Instant now) {
        consumeRejectedSelection(selectionMessage, null, now);
        return RoutingAction.rejected();
    }

    private void consumeRejectedSelection(
            WechatConversationMessage selectionMessage,
            Long familyId,
            Instant now) {
        selectionMessage.setFamilyId(familyId);
        selectionMessage.setProcessingStatus(PROCESSED);
        selectionMessage.setProcessedAt(now);
        messageRepository.save(selectionMessage);
    }

    private WechatChatContext lockedContext(WechatConversationMessage message) {
        return contextRepository
                .findByWechatBindIdForUpdate(message.getWechatBindId())
                .orElseGet(() -> {
                    if (message.getUserId() == null) {
                        throw new IllegalArgumentException("未绑定消息不能建立家庭路由上下文");
                    }
                    WechatChatContext created = new WechatChatContext();
                    created.setWechatBindId(message.getWechatBindId());
                    created.setUserId(message.getUserId());
                    return contextRepository.save(created);
                });
    }

    private long nextRevision(WechatChatContext context) {
        long revision = currentRevision(context) + 1;
        context.setRouteRevision(revision);
        return revision;
    }

    private long currentRevision(WechatChatContext context) {
        return context.getRouteRevision() == null ? 0L : context.getRouteRevision();
    }

    private void clearInvalidChallenge(WechatChatContext context) {
        context.setPendingFamilySelection(null);
        context.setRouteRevision(currentRevision(context) + 1);
        contextRepository.save(context);
    }

    private Map<String, Object> toMap(
            long pendingMessageId,
            FamilySelectionChallenge challenge) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(PENDING_MESSAGE_ID, pendingMessageId);
        value.put(WECHAT_BIND_ID, challenge.wechatBindId());
        value.put(ROUTE_REVISION, challenge.routeRevision());
        value.put(EXPIRES_AT, challenge.expiresAt().toString());
        value.put(CANDIDATE_FAMILY_IDS, challenge.candidateFamilyIds());
        return value;
    }

    private PendingChallenge parsePending(Map<String, Object> value) {
        long pendingMessageId = requiredLong(value.get(PENDING_MESSAGE_ID));
        long wechatBindId = requiredLong(value.get(WECHAT_BIND_ID));
        long routeRevision = requiredLong(value.get(ROUTE_REVISION));
        Instant expiresAt = Instant.parse(requiredText(value.get(EXPIRES_AT)));
        Object rawIds = value.get(CANDIDATE_FAMILY_IDS);
        if (!(rawIds instanceof List<?> ids) || ids.isEmpty()) {
            throw new IllegalArgumentException("家庭候选快照为空");
        }
        List<Long> candidateFamilyIds = ids.stream()
                .map(this::requiredLong)
                .toList();
        return new PendingChallenge(
                pendingMessageId,
                wechatBindId,
                routeRevision,
                expiresAt,
                candidateFamilyIds);
    }

    private long requiredLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        throw new IllegalArgumentException("家庭选择快照数字字段无效");
    }

    private String requiredText(Object value) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalArgumentException("家庭选择快照文本字段无效");
    }

    private record PendingChallenge(
            long pendingMessageId,
            long wechatBindId,
            long routeRevision,
            Instant expiresAt,
            List<Long> candidateFamilyIds) {
    }

    public enum ActionType {
        ROUTED,
        CHALLENGE,
        NO_FAMILY,
        REPLAY,
        REJECTED,
        NO_CHALLENGE,
        ALREADY_HANDLED
    }

    public record RoutingAction(
            ActionType type,
            Long messageId,
            Long familyId,
            List<FamilyOption> options) {

        public RoutingAction {
            options = List.copyOf(options);
        }

        static RoutingAction routed(long messageId, long familyId) {
            return new RoutingAction(ActionType.ROUTED, messageId, familyId, List.of());
        }

        static RoutingAction challenge(List<FamilyOption> options) {
            return new RoutingAction(ActionType.CHALLENGE, null, null, options);
        }

        static RoutingAction noFamily() {
            return new RoutingAction(ActionType.NO_FAMILY, null, null, List.of());
        }

        static RoutingAction replay(long messageId, long familyId) {
            return new RoutingAction(ActionType.REPLAY, messageId, familyId, List.of());
        }

        static RoutingAction rejected() {
            return new RoutingAction(ActionType.REJECTED, null, null, List.of());
        }

        static RoutingAction noChallenge() {
            return new RoutingAction(ActionType.NO_CHALLENGE, null, null, List.of());
        }

        static RoutingAction alreadyHandled() {
            return new RoutingAction(ActionType.ALREADY_HANDLED, null, null, List.of());
        }
    }
}
