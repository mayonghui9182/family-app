package com.familyapp.wechat.conversation;

import com.familyapp.category.Category;
import com.familyapp.category.CategoryRepository;
import com.familyapp.profile.PersonProfile;
import com.familyapp.profile.PersonProfileRepository;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.ActionType;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.CandidateTask;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.ExperienceContext;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 提交 AI 生成的候选任务版本。
 *
 * <p>候选快照只新增不更新；会话修订、回复消息、来源消息完成状态和绑定租约
 * 在同一短事务内提交。落库前重新按家庭读取分类和成员档案，不能把模型返回的
 * ID 当成授权依据。</p>
 */
@Service
public class WechatConversationCandidateActionService {

    private static final String IDEMPOTENCY_PREFIX = "ai-candidate:";
    private static final String MODEL_NAME = "agent-service";
    private static final String PROMPT_VERSION = "wechat-task-conversation-v1";
    private static final int MAX_TASKS = 20;
    private static final int MAX_SLOT_PATCH = 20;

    private final WechatConversationMessageRepository messageRepository;
    private final WechatTaskConversationRepository conversationRepository;
    private final WechatCandidateVersionRepository candidateRepository;
    private final WechatChatContextRepository contextRepository;
    private final CategoryRepository categoryRepository;
    private final WechatConversationAiResponseFence responseFence;
    private final PersonProfileRepository profileRepository;
    private final WechatConversationProperties properties;
    private final WechatConversationOutboxService outbox;

    public WechatConversationCandidateActionService(
            WechatConversationMessageRepository messageRepository,
            WechatTaskConversationRepository conversationRepository,
            WechatCandidateVersionRepository candidateRepository,
            WechatChatContextRepository contextRepository,
            CategoryRepository categoryRepository,
            WechatConversationAiResponseFence responseFence,
            PersonProfileRepository profileRepository,
            WechatConversationProperties properties,
            WechatConversationOutboxService outbox) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.candidateRepository = candidateRepository;
        this.contextRepository = contextRepository;
        this.categoryRepository = categoryRepository;
        this.profileRepository = profileRepository;
        this.responseFence = responseFence;
        this.properties = properties;
        this.outbox = outbox;
    }
    @Transactional
    public ApplyResult apply(
            MessageLease lease,
            PreparedTurn preparedTurn,
            TurnCallResult callResult,
            Instant now) {
        TurnAction action = requirePresentAction(lease, preparedTurn, callResult);
        TurnRequest request = preparedTurn.turnRequest();

        WechatConversationMessage source = messageRepository
                .findByIdAndWechatBindId(lease.messageId(), lease.wechatBindId())
                .orElseThrow(() -> new IllegalStateException("候选动作来源消息不存在"));
        requireSourceScope(source, preparedTurn, request);

        var existing = candidateRepository
                .findByConversationIdAndFamilyIdAndSourceMessageId(
                        preparedTurn.conversationId(),
                        preparedTurn.familyId(),
                        source.getId());
        if (existing.isPresent()) {
            return duplicateResult(existing.get(), source, preparedTurn);
        }

        requireOwnedSource(source, lease, now);
        var fenceResult = responseFence.lockAndCheck(
                lease, preparedTurn, source, now);
        if (!fenceResult.current()) {
            return ApplyResult.stale(fenceResult.staleReason());
        }
        WechatTaskConversation conversation = fenceResult.conversation();
        requireConversationScope(conversation, preparedTurn);

        existing = candidateRepository
                .findByConversationIdAndFamilyIdAndSourceMessageId(
                        preparedTurn.conversationId(),
                        preparedTurn.familyId(),
                        source.getId());
        if (existing.isPresent()) {
            return duplicateResult(existing.get(), source, preparedTurn);
        }

        validateCurrentFamilyReferences(action.tasks(), request, preparedTurn.familyId());
        int nextRevision = Math.addExact(conversation.getCurrentRevision(), 1);
        WechatCandidateVersion candidate = saveCandidate(
                action, request, source, preparedTurn, nextRevision);

        conversation.setSlotState(mergeSlotPatch(
                conversation.getSlotState(), action.slotPatch(), request));
        conversation.setCurrentRevision(nextRevision);
        conversation.setStatus(ConversationStatus.AWAITING_CONFIRMATION);
        conversation.setLastActivityAt(now);
        conversation.setExpiresAt(now.plus(properties.getTtlHours(), ChronoUnit.HOURS));
        conversationRepository.save(conversation);

        WechatConversationMessage reply = saveReply(
                source, candidate.getRenderedPreview(), idempotencyKey(source.getId()), now);
        int completed = messageRepository.completeProcessing(
                lease.messageId(), lease.wechatBindId(), lease.workerId(), now);
        if (completed == 0) {
            throw new IllegalStateException("候选动作来源消息完成状态提交冲突");
        }
        if (contextRepository.releaseLease(
                lease.wechatBindId(), lease.workerId(), now) == 0) {
            throw new IllegalStateException("候选动作绑定租约释放失败");
        }
        return ApplyResult.applied(candidate.getId(), reply.getId(), nextRevision);
    }
    private TurnAction requirePresentAction(
            MessageLease lease,
            PreparedTurn preparedTurn,
            TurnCallResult callResult) {
        if (preparedTurn == null
                || !preparedTurn.requiresAi()
                || !Objects.equals(preparedTurn.lease(), lease)) {
            throw new IllegalArgumentException("候选动作准备结果不匹配");
        }
        if (callResult == null
                || !callResult.accepted()
                || !callResult.advanceRevision()
                || callResult.action() == null
                || callResult.action().action() != ActionType.PRESENT_CANDIDATE) {
            throw new IllegalArgumentException("候选动作未通过校验");
        }

        TurnAction action = callResult.action();
        if (action.reply() == null
                || action.reply().isBlank()
                || action.reply().length() > 1_200) {
            throw new IllegalArgumentException("候选回复不符合约束");
        }
        if (action.tasks() == null
                || action.tasks().isEmpty()
                || action.tasks().size() > MAX_TASKS) {
            throw new IllegalArgumentException("候选任务数量不符合约束");
        }
        if (action.slotPatch() == null || action.slotPatch().size() > MAX_SLOT_PATCH) {
            throw new IllegalArgumentException("候选 slot_patch 不符合约束");
        }

        TurnRequest request = preparedTurn.turnRequest();
        if (request == null
                || !Objects.equals(request.messageId(), lease.messageId())
                || !Objects.equals(request.conversationId(), preparedTurn.conversationId())
                || request.family() == null
                || !Objects.equals(request.family().familyId(), preparedTurn.familyId())
                || !Objects.equals(request.family().actorUserId(), preparedTurn.userId())) {
            throw new IllegalArgumentException("候选动作请求作用域不匹配");
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
            throw new IllegalStateException("候选动作来源消息作用域不匹配");
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
            throw new IllegalStateException("候选动作来源消息租约已失效");
        }
    }

    private void requireConversationScope(
            WechatTaskConversation conversation,
            PreparedTurn preparedTurn) {
        if (!conversation.getStatus().isRecoverable()
                || !Objects.equals(conversation.getWechatBindId(),
                preparedTurn.lease().wechatBindId())
                || !Objects.equals(conversation.getUserId(), preparedTurn.userId())) {
            throw new IllegalStateException("候选动作目标会话作用域不匹配");
        }
    }
    private void validateCurrentFamilyReferences(
            List<CandidateTask> tasks,
            TurnRequest request,
            Long familyId) {
        Set<Long> requestCategoryIds = request.categories().stream()
                .map(WechatConversationAgentClient.CategoryReference::categoryId)
                .collect(Collectors.toUnmodifiableSet());
        Set<Long> requestProfileIds = request.members().stream()
                .map(WechatConversationAgentClient.MemberReference::profileId)
                .collect(Collectors.toUnmodifiableSet());
        Set<Long> currentCategoryIds = categoryRepository
                .findByFamilyIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(familyId)
                .stream()
                .map(Category::getId)
                .collect(Collectors.toUnmodifiableSet());
        Set<Long> currentProfileIds = profileRepository
                .findByFamilyIdAndDeletedAtIsNull(familyId)
                .stream()
                .map(PersonProfile::getId)
                .collect(Collectors.toUnmodifiableSet());

        for (CandidateTask task : tasks) {
            validateTask(task);
            if (task.categoryName() != null && request.family().actorRole() == com.familyapp.family.FamilyRole.CHILD
                    && request.categories().stream().noneMatch(item ->
                        WechatCategoryNames.key(item.name()).equals(WechatCategoryNames.key(task.categoryName())))) {
                throw new IllegalArgumentException("儿童不能建议创建新分类");
            }
            if (task.categoryId() != null
                    && (!requestCategoryIds.contains(task.categoryId())
                    || !currentCategoryIds.contains(task.categoryId()))) {
                throw new IllegalArgumentException("候选引用了其他家庭分类");
            }
            if (task.assigneeProfileId() != null
                    && (!requestProfileIds.contains(task.assigneeProfileId())
                    || !currentProfileIds.contains(task.assigneeProfileId()))) {
                throw new IllegalArgumentException("候选引用了其他家庭成员");
            }
        }
    }

    private void validateTask(CandidateTask task) {
        if (task == null
                || task.title() == null
                || task.title().isBlank()
                || task.title().length() > 200
                || task.description() == null
                || task.description().length() > 2_000
                || task.priority() == null) {
            throw new IllegalArgumentException("候选任务字段不符合约束");
        }
    }

    private WechatCandidateVersion saveCandidate(
            TurnAction action,
            TurnRequest request,
            WechatConversationMessage source,
            PreparedTurn preparedTurn,
            int revision) {
        WechatCandidateVersion candidate = new WechatCandidateVersion();
        candidate.setConversationId(preparedTurn.conversationId());
        candidate.setFamilyId(preparedTurn.familyId());
        candidate.setRevision(revision);
        candidate.setCandidatePayload(candidatePayload(action.tasks()));
        candidate.setRenderedPreview(WechatCandidatePreview.render(action.tasks(), request, revision));
        candidate.setSourceMessageId(source.getId());
        candidate.setModelName(MODEL_NAME);
        candidate.setPromptVersion(PROMPT_VERSION);
        candidate.setExperienceRefs(experienceRefs(request.experiences()));
        return candidateRepository.save(candidate);
    }

    private List<Map<String, Object>> candidatePayload(List<CandidateTask> tasks) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (CandidateTask task : tasks) {
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("title", task.title());
            stored.put("description", task.description());
            stored.put("due_date", task.dueDate() == null
                    ? null
                    : task.dueDate().toString());
            stored.put("priority", task.priority().name());
            stored.put("category_id", task.categoryId());
            if (task.categoryName() != null) stored.put("category_name", task.categoryName());
            stored.put("assignee_profile_id", task.assigneeProfileId());
            payload.add(stored);
        }
        return payload;
    }

    private List<Map<String, Object>> experienceRefs(
            List<ExperienceContext> experiences) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (ExperienceContext experience : experiences) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("experience_id", experience.experienceId());
            ref.put("version", experience.version());
            refs.add(ref);
        }
        return refs;
    }
    private Map<String, Object> mergeSlotPatch(
            Map<String, Object> current,
            Map<String, SlotValue> patch,
            TurnRequest request) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (current != null) {
            merged.putAll(current);
        }
        Set<Long> allowedExperienceIds = request.experiences().stream()
                .map(ExperienceContext::experienceId)
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
        List<String> parts = WechatCandidatePreview.segments(content);
        WechatConversationMessage first = null;
        for (int index = 0; index < parts.size(); index++) {
            String key = index == 0 ? idempotencyKey : idempotencyKey + ":part:" + (index + 1);
            WechatConversationMessage saved = saveReplyPart(source, parts.get(index), key, now);
            if (first == null) first = saved;
        }
        return Objects.requireNonNull(first);
    }

    private WechatConversationMessage saveReplyPart(
            WechatConversationMessage source, String content, String idempotencyKey, Instant now) {
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

    private ApplyResult duplicateResult(
            WechatCandidateVersion candidate,
            WechatConversationMessage source,
            PreparedTurn preparedTurn) {
        String key = idempotencyKey(source.getId());
        WechatConversationMessage reply = messageRepository
                .findByWechatAccountIdAndExternalMsgIdAndWechatBindIdAndFamilyIdAndConversationId(
                        source.getWechatAccountId(),
                        key,
                        preparedTurn.lease().wechatBindId(),
                        preparedTurn.familyId(),
                        preparedTurn.conversationId())
                .orElseThrow(() -> new IllegalStateException("候选幂等结果缺少回复消息"));
        if (reply.getDirection() != MessageDirection.OUTBOUND
                || !Objects.equals(reply.getUserId(), source.getUserId())) {
            throw new IllegalStateException("候选幂等键已被其他消息占用");
        }
        return ApplyResult.duplicate(
                candidate.getId(), reply.getId(), candidate.getRevision());
    }

    static String idempotencyKey(long sourceMessageId) {
        return IDEMPOTENCY_PREFIX + sourceMessageId;
    }

    public record ApplyResult(
            boolean applied,
            boolean duplicate,
            long candidateVersionId,
            long replyMessageId,
            int revision,
            boolean stale,
            StaleReason staleReason) {

        static ApplyResult applied(
                long candidateVersionId,
                long replyMessageId,
                int revision) {
            return new ApplyResult(
                    true, false, candidateVersionId, replyMessageId, revision,
                    false, null);
        }

        static ApplyResult duplicate(
                long candidateVersionId,
                long replyMessageId,
                int revision) {
            return new ApplyResult(
                    false, true, candidateVersionId, replyMessageId, revision,
                    false, null);
        }

        static ApplyResult stale(StaleReason reason) {
            return new ApplyResult(
                    false, false, 0, 0, 0,
                    true, Objects.requireNonNull(reason, "reason"));
        }
    }
}
