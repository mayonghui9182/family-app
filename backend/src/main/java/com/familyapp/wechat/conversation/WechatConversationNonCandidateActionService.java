package com.familyapp.wechat.conversation;

import com.familyapp.wechat.conversation.WechatConversationAgentClient.ActionType;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.SlotSource;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.SlotValue;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnAction;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnCallResult;
import com.familyapp.wechat.conversation.WechatConversationAiResponseFence.StaleReason;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnRequest;
import com.familyapp.wechat.conversation.WechatConversationLeaseService.MessageLease;
import com.familyapp.wechat.conversation.WechatConversationTurnPreparationService.PreparedTurn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 提交不产生候选版本的 AI 会话动作。
 *
 * <p>ASK/REFUSE 的槽位、回复历史、来源消息完成状态和绑定租约在同一短事务内提交。
 * 回复使用来源入站消息 ID 生成稳定幂等键，事务结果未知时可安全重试。</p>
 */
@Service
public class WechatConversationNonCandidateActionService {

    private static final String IDEMPOTENCY_PREFIX = "ai-action:";
    private final WechatConversationAiResponseFence responseFence;

    private final WechatConversationMessageRepository messageRepository;
    private final WechatTaskConversationRepository conversationRepository;
    private final WechatChatContextRepository contextRepository;
    private final WechatConversationProperties properties;
    private final WechatConversationOutboxService outbox;

    public WechatConversationNonCandidateActionService(
            WechatConversationMessageRepository messageRepository,
            WechatTaskConversationRepository conversationRepository,
            WechatChatContextRepository contextRepository,
            WechatConversationAiResponseFence responseFence,
            WechatConversationProperties properties,
            WechatConversationOutboxService outbox) {
        this.responseFence = responseFence;
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.contextRepository = contextRepository;
        this.properties = properties;
        this.outbox = outbox;
    }

    @Transactional
    public ApplyResult apply(
            MessageLease lease,
            PreparedTurn preparedTurn,
            TurnCallResult callResult,
            Instant now) {
        TurnAction action = requireSupportedAction(lease, preparedTurn, callResult);
        TurnRequest request = preparedTurn.turnRequest();

        WechatConversationMessage source = messageRepository
                .findByIdAndWechatBindId(lease.messageId(), lease.wechatBindId())
                .orElseThrow(() -> new IllegalStateException("AI 动作来源消息不存在"));
        requireSourceScope(source, preparedTurn, request);

        String idempotencyKey = idempotencyKey(source.getId());
        var existingReply = messageRepository
                .findByWechatAccountIdAndExternalMsgIdAndWechatBindIdAndFamilyIdAndConversationId(
                        source.getWechatAccountId(),
                        idempotencyKey,
                        lease.wechatBindId(),
                        preparedTurn.familyId(),
                        preparedTurn.conversationId());
        if (existingReply.isPresent()) {
            requireReplyScope(existingReply.get(), source);
            return ApplyResult.duplicate(existingReply.get().getId());
        }

        requireOwnedSource(source, lease, now);
        var fenceResult = responseFence.lockAndCheck(
                lease, preparedTurn, source, now);
        if (!fenceResult.current()) {
            return ApplyResult.stale(fenceResult.staleReason());
        }
        WechatTaskConversation conversation = fenceResult.conversation();
        requireConversationScope(conversation, preparedTurn);

        conversation.setSlotState(mergeSlotPatch(
                conversation.getSlotState(), action.slotPatch(), request));
        if (action.action() == ActionType.ASK_CLARIFICATION) {
            conversation.setStatus(ConversationStatus.ACTIVE);
        }
        conversation.setLastActivityAt(now);
        conversation.setExpiresAt(now.plus(properties.getTtlHours(), ChronoUnit.HOURS));
        conversationRepository.save(conversation);

        WechatConversationMessage reply =
                saveReply(source, action.reply(), idempotencyKey, now);
        int completed = messageRepository.completeProcessing(
                lease.messageId(),
                lease.wechatBindId(),
                lease.workerId(),
                now);
        if (completed == 0) {
            throw new IllegalStateException("AI 动作来源消息完成状态提交冲突");
        }
        if (contextRepository.releaseLease(
                lease.wechatBindId(), lease.workerId(), now) == 0) {
            throw new IllegalStateException("AI 动作绑定租约释放失败");
        }
        return ApplyResult.applied(reply.getId());
    }

    private TurnAction requireSupportedAction(
            MessageLease lease,
            PreparedTurn preparedTurn,
            TurnCallResult callResult) {
        if (preparedTurn == null
                || !preparedTurn.requiresAi()
                || !Objects.equals(preparedTurn.lease(), lease)) {
            throw new IllegalArgumentException("AI 动作准备结果不匹配");
        }
        if (callResult == null
                || !callResult.accepted()
                || callResult.advanceRevision()
                || callResult.action() == null) {
            throw new IllegalArgumentException("AI 动作未通过校验");
        }

        TurnAction action = callResult.action();
        if (action.action() != ActionType.ASK_CLARIFICATION
                && action.action() != ActionType.REFUSE_OR_HELP) {
            throw new IllegalArgumentException("该服务只处理 ASK/REFUSE 动作");
        }
        if (action.reply() == null || action.reply().isBlank()) {
            throw new IllegalArgumentException("AI 动作回复不能为空");
        }
        if (action.tasks() == null || !action.tasks().isEmpty()) {
            throw new IllegalArgumentException("ASK/REFUSE 不得携带候选任务");
        }

        TurnRequest request = preparedTurn.turnRequest();
        if (request == null
                || !Objects.equals(request.messageId(), lease.messageId())
                || !Objects.equals(request.conversationId(), preparedTurn.conversationId())
                || request.family() == null
                || !Objects.equals(request.family().familyId(), preparedTurn.familyId())
                || !Objects.equals(request.family().actorUserId(), preparedTurn.userId())) {
            throw new IllegalArgumentException("AI 动作请求作用域不匹配");
        }
        return action;
    }

    private void requireSourceScope(
            WechatConversationMessage source,
            PreparedTurn preparedTurn,
            TurnRequest request) {
        if (source.getDirection() != MessageDirection.INBOUND
                || !Objects.equals(source.getFamilyId(), preparedTurn.familyId())
                || !Objects.equals(source.getConversationId(), preparedTurn.conversationId())
                || !Objects.equals(source.getUserId(), preparedTurn.userId())
                || !Objects.equals(request.messageId(), source.getId())) {
            throw new IllegalStateException("AI 动作来源消息作用域不匹配");
        }
    }

    private void requireOwnedSource(
            WechatConversationMessage source,
            MessageLease lease,
            Instant now) {
        if (source.getProcessingStatus() != MessageProcessingStatus.PROCESSING
                || !Objects.equals(source.getLeaseOwner(), lease.workerId())
                || source.getLeaseUntil() == null
                || !source.getLeaseUntil().isAfter(now)) {
            throw new IllegalStateException("AI 动作来源消息租约已失效");
        }
    }

    private void requireConversationScope(
            WechatTaskConversation conversation,
            PreparedTurn preparedTurn) {
        if (!conversation.getStatus().isRecoverable()
                || !Objects.equals(conversation.getWechatBindId(),
                preparedTurn.lease().wechatBindId())
                || !Objects.equals(conversation.getUserId(), preparedTurn.userId())) {
            throw new IllegalStateException("AI 动作目标会话作用域不匹配");
        }
    }

    private Map<String, Object> mergeSlotPatch(
            Map<String, Object> current,
            Map<String, SlotValue> patch,
            TurnRequest request) {
        if (patch == null) {
            throw new IllegalArgumentException("slot_patch 不能为空");
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (current != null) {
            merged.putAll(current);
        }

        Set<Long> allowedExperienceIds = request.experiences().stream()
                .map(WechatConversationAgentClient.ExperienceContext::experienceId)
                .collect(Collectors.toUnmodifiableSet());
        for (Map.Entry<String, SlotValue> entry : patch.entrySet()) {
            if (!request.allowedSlotKeys().contains(entry.getKey())) {
                throw new IllegalArgumentException("slot_patch 包含未允许槽位");
            }
            validateSlotSource(entry.getValue(), request.messageId(), allowedExperienceIds);

            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("value", entry.getValue().value());
            stored.put("source", entry.getValue().source().name());
            if (entry.getValue().sourceMessageId() != null) {
                stored.put("source_message_id", entry.getValue().sourceMessageId());
            }
            if (entry.getValue().experienceId() != null) {
                stored.put("experience_id", entry.getValue().experienceId());
            }
            merged.put(entry.getKey(), stored);
        }
        return merged;
    }

    private void validateSlotSource(
            SlotValue slot,
            Long sourceMessageId,
            Set<Long> allowedExperienceIds) {
        if (slot == null || slot.source() == null) {
            throw new IllegalArgumentException("槽位来源不完整");
        }
        validateSlotValue(slot.value());
        if (slot.source() == SlotSource.EXPERIENCE_DEFAULT) {
            if (slot.sourceMessageId() != null
                    || slot.experienceId() == null
                    || !allowedExperienceIds.contains(slot.experienceId())) {
                throw new IllegalArgumentException("经验槽位来源无效");
            }
            return;
        }
        if (!Objects.equals(slot.sourceMessageId(), sourceMessageId)
                || slot.experienceId() != null) {
            throw new IllegalArgumentException("消息槽位来源无效");
        }
    }

    private void validateSlotValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
            return;
        }
        if (value instanceof List<?> list && list.stream().allMatch(item ->
                item instanceof String || item instanceof Integer || item instanceof Long)) {
            return;
        }
        throw new IllegalArgumentException("槽位值类型不受支持");
    }

    private WechatConversationMessage saveReply(
            WechatConversationMessage source,
            String content,
            String idempotencyKey,
            Instant now) {
        WechatConversationMessage reply = new WechatConversationMessage();
        reply.setWechatAccountId(source.getWechatAccountId());
        reply.setExternalMsgId(idempotencyKey);
        reply.setOpenid(source.getOpenid());
        reply.setDirection(MessageDirection.OUTBOUND);
        reply.setContent(content);
        reply.setWechatBindId(source.getWechatBindId());
        reply.setUserId(source.getUserId());
        reply.setFamilyId(source.getFamilyId());
        reply.setConversationId(source.getConversationId());
        reply.setProcessingStatus(MessageProcessingStatus.PROCESSED);
        reply.setAvailableAt(now);
        reply.setReceivedAt(now);
        reply.setProcessedAt(now);
        reply = messageRepository.save(reply);
        outbox.enqueueExisting(reply, idempotencyKey, now);
        return reply;
    }

    private void requireReplyScope(
            WechatConversationMessage reply,
            WechatConversationMessage source) {
        if (reply.getDirection() != MessageDirection.OUTBOUND
                || !Objects.equals(reply.getUserId(), source.getUserId())) {
            throw new IllegalStateException("AI 动作幂等键已被其他消息占用");
        }
    }

    static String idempotencyKey(long sourceMessageId) {
        return IDEMPOTENCY_PREFIX + sourceMessageId;
    }

    public record ApplyResult(
            boolean applied,
            boolean duplicate,
            long replyMessageId,
            boolean stale,
            StaleReason staleReason) {

        static ApplyResult applied(long replyMessageId) {
            return new ApplyResult(
                    true, false, replyMessageId, false, null);
        }

        static ApplyResult duplicate(long replyMessageId) {
            return new ApplyResult(
                    false, true, replyMessageId, false, null);
        }

        static ApplyResult stale(StaleReason reason) {
            return new ApplyResult(
                    false, false, 0, true, Objects.requireNonNull(reason, "reason"));
        }
    }
}

