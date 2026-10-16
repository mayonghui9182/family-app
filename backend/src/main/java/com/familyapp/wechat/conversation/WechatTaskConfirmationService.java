package com.familyapp.wechat.conversation;

import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.task.Task;
import com.familyapp.task.TaskCreationCommandService;
import com.familyapp.task.dto.CreateTaskRequest;
import com.familyapp.wechat.conversation.WechatConversationLeaseService.MessageLease;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 精确微信确认：消息、当前不可变候选、任务和确认关联在同一家庭事务内裁决。 */
@Service
public class WechatTaskConfirmationService {
    @org.springframework.beans.factory.annotation.Autowired
    private WechatConversationOperationalMetrics metrics;
    private final WechatChatContextRepository contexts;
    private final WechatTaskConversationRepository conversations;
    private final WechatConversationMessageRepository messages;
    private final WechatCandidateVersionRepository candidates;
    private final WechatTaskConfirmationRepository confirmations;
    private final WechatConfirmationTaskRepository links;
    private final WechatFamilyAccessGuard accessGuard;
    private final WechatConversationCommandParser parser;
    private final TaskCreationCommandService taskCommands;
    private final ObjectMapper mapper;
    private final com.familyapp.family.FamilyRepository families;
    private final WechatCandidateCategoryResolver categoryResolver;

    public WechatTaskConfirmationService(WechatChatContextRepository contexts,
            WechatTaskConversationRepository conversations,
            WechatConversationMessageRepository messages,
            WechatCandidateVersionRepository candidates,
            WechatTaskConfirmationRepository confirmations,
            WechatConfirmationTaskRepository links,
            WechatFamilyAccessGuard accessGuard,
            WechatConversationCommandParser parser,
            TaskCreationCommandService taskCommands,
            com.familyapp.family.FamilyRepository families,
            WechatCandidateCategoryResolver categoryResolver,
            ObjectMapper mapper) {
        this.contexts = contexts;
        this.conversations = conversations;
        this.messages = messages;
        this.candidates = candidates;
        this.confirmations = confirmations;
        this.links = links;
        this.accessGuard = accessGuard;
        this.parser = parser;
        this.taskCommands = taskCommands;
        this.families = families;
        this.categoryResolver = categoryResolver;
        this.mapper = mapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    @Transactional
    public ConfirmationResult confirm(MessageLease lease, long userId, long familyId,
                                      long conversationId, Instant now) {
        // 与家庭删除一致，先锁有效家庭，再锁绑定及会话；同家庭的新分类确认串行。
        families.findActiveByIdForUpdate(familyId).orElseThrow(WechatFamilyAccessDeniedException::new);
        WechatChatContext context = contexts.findByWechatBindIdForUpdate(lease.wechatBindId())
                .orElseThrow(WechatFamilyAccessDeniedException::new);
        if (!Objects.equals(context.getUserId(), userId)
                || !Objects.equals(context.getCurrentFamilyId(), familyId)
                || !Objects.equals(context.getLeaseOwner(), lease.workerId())
                || context.getLeaseUntil() == null || !context.getLeaseUntil().isAfter(now)) {
            throw new WechatFamilyAccessDeniedException();
        }
        WechatTaskConversation conversation = conversations
                .findByIdAndFamilyIdForUpdate(conversationId, familyId)
                .orElseThrow(WechatFamilyAccessDeniedException::new);
        if (!Objects.equals(conversation.getUserId(), userId)
                || !Objects.equals(conversation.getWechatBindId(), lease.wechatBindId())) {
            throw new WechatFamilyAccessDeniedException();
        }
        var actor = accessGuard.requireAccess(userId, familyId, WechatFamilyAccessGuard.AccessOperation.CONFIRM_TASK);

        WechatConversationMessage source = messages.findByIdAndFamilyId(lease.messageId(), familyId)
                .orElseThrow(WechatFamilyAccessDeniedException::new);
        if (source.getDirection() != MessageDirection.INBOUND
                || !Objects.equals(source.getWechatBindId(), lease.wechatBindId())
                || !Objects.equals(source.getUserId(), userId)
                || (source.getConversationId() != null
                    && !Objects.equals(source.getConversationId(), conversationId))
                || source.getProcessingStatus() != MessageProcessingStatus.PROCESSING
                || !Objects.equals(source.getLeaseOwner(), lease.workerId())
                || source.getLeaseUntil() == null || !source.getLeaseUntil().isAfter(now)) {
            throw new WechatFamilyAccessDeniedException();
        }
        var command = parser.parse(source.getContent());
        if (command.type() != WechatConversationCommandParser.CommandType.CONFIRM_CREATE) {
            return ConfirmationResult.rejected("请先查看候选版本，再按提示发送“确认创建 N”。");
        }
        int revision = conversation.getCurrentRevision();
        if ((command.number() == null && revision != 1)
                || (command.number() != null && command.number() != revision)) {
            return ConfirmationResult.rejected("当前候选是版本 " + revision
                    + "，请查看后回复“确认创建 " + revision + "”。");
        }
        String confirmationKey = "wechat-confirm:" + familyId + ":" + conversationId + ":" + revision;
        // 持有会话锁后读取已提交凭据；并发确认在锁释放后只能看到首次结果。
        var existing = confirmations.findByIdempotencyKeyAndFamilyId(confirmationKey, familyId)
                .or(() -> confirmations.findByConversationIdAndFamilyIdAndCandidateRevision(
                        conversationId, familyId, revision));
        if (existing.isPresent()) {
            if (metrics != null) metrics.event(WechatConversationOperationalMetrics.Event.CONFIRMATION_REPLAY);
            WechatTaskConfirmation committed = existing.get();
            if (!Objects.equals(committed.getConversationId(), conversationId)
                    || !Objects.equals(committed.getCandidateRevision(), revision)
                    || !Objects.equals(committed.getConfirmedBy(), userId)) {
                throw new WechatFamilyAccessDeniedException();
            }
            List<Long> taskIds = links.findByConfirmationIdAndFamilyIdOrderByCandidateItemIndexAsc(
                    committed.getId(), familyId).stream().map(WechatConfirmationTask::getTaskId).toList();
            if (taskIds.isEmpty()) {
                throw new IllegalStateException("已提交确认缺少任务关联，不能重新创建任务");
            }
            // 新确认消息也固定到原会话，后续恢复处理时不能被新活动会话重新路由。
            source.setConversationId(conversationId);
            messages.save(source);
            return new ConfirmationResult(true, committed.getId(), taskIds,
                    "已创建 " + taskIds.size() + " 项任务。");
        }
        // 追问不会删除不可变候选；明确指定当前版本时可确认仍有效的原快照。
        boolean confirmable = conversation.getStatus() == ConversationStatus.AWAITING_CONFIRMATION
                || (conversation.getStatus() == ConversationStatus.ACTIVE && command.number() != null);
        if (!confirmable || conversation.getExpiresAt() == null || !conversation.getExpiresAt().isAfter(now)) {
            if (conversation.getStatus() == ConversationStatus.ACTIVE && command.number() == null
                    && revision > 0 && conversation.getExpiresAt() != null && conversation.getExpiresAt().isAfter(now)) {
                return ConfirmationResult.rejected("候选第 " + revision
                        + " 版仍保留，请核对后明确回复“确认创建 " + revision + "”。");
            }
            return ConfirmationResult.rejected("当前没有有效的待确认候选，请重新描述任务。");
        }
        WechatCandidateVersion candidate = candidates.findByConversationIdAndFamilyIdAndRevision(
                conversationId, familyId, revision).orElseThrow(() -> invalidCandidate());
        List<Map<String, Object>> payload = candidate.getCandidatePayload();
        if (payload == null || payload.isEmpty() || payload.size() > 20) {
            throw invalidCandidate();
        }
        // 分类建议解析、整批创建和确认凭据共用事务；任一步失败都回滚新分类。
        List<CreateTaskRequest> requests;
        try {
            var resolved = categoryResolver.resolve(familyId, userId, actor.role(), payload);
            requests = resolved.stream().map(item -> mapper.convertValue(item, CreateTaskRequest.class)).toList();
        } catch (IllegalArgumentException exception) {
            throw invalidCandidate();
        }
        List<Task> tasks = taskCommands.createPendingAgentBatch(familyId, userId, requests);
        WechatTaskConfirmation confirmation = new WechatTaskConfirmation();
        confirmation.setFamilyId(familyId);
        confirmation.setConversationId(conversationId);
        confirmation.setCandidateVersionId(candidate.getId());
        confirmation.setCandidateRevision(revision);
        confirmation.setCandidateHash(hash(payload));
        confirmation.setConfirmationMessageId(source.getId());
        confirmation.setConfirmedBy(userId);
        confirmation.setIdempotencyKey(confirmationKey);
        confirmation.setCommittedAt(now);
        confirmations.save(confirmation);
        for (int index = 0; index < tasks.size(); index++) {
            Task task = tasks.get(index);
            WechatConfirmationTask link = new WechatConfirmationTask();
            link.setFamilyId(familyId);
            link.setConfirmationId(confirmation.getId());
            link.setTaskId(task.getId());
            link.setCandidateItemIndex(index);
            link.setInitialTaskSnapshot(snapshot(task));
            links.save(link);
        }
        source.setConversationId(conversationId);
        messages.save(source);
        conversation.setStatus(ConversationStatus.CONFIRMED);
        conversation.setActiveSlot(null);
        conversation.setPausedFromStatus(null);
        conversation.setLastActivityAt(now);
        conversations.save(conversation);
        return new ConfirmationResult(true, confirmation.getId(),
                tasks.stream().map(Task::getId).toList(), "已创建 " + tasks.size() + " 项任务。");
    }

    private Map<String, Object> snapshot(Task task) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("task_id", task.getId());
        value.put("family_id", task.getFamilyId());
        value.put("created_by", task.getCreatedBy());
        value.put("title", task.getTitle());
        value.put("description", task.getDescription());
        value.put("due_date", task.getDueDate() == null ? null : task.getDueDate().toString());
        value.put("priority", task.getPriority().name());
        value.put("status", task.getStatus().name());
        value.put("source", task.getSource().name());
        value.put("category_id", task.getCategoryId());
        value.put("assignee_id", task.getAssigneeId());
        value.put("assignee_profile_id", task.getAssigneeProfileId());
        return value;
    }

    private String hash(List<Map<String, Object>> payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("确认快照无法生成审计哈希", exception);
        }
    }

    private ApiException invalidCandidate() {
        return new ApiException(ResultCode.BAD_REQUEST_VALIDATION, "候选任务无效，请修改后重新确认");
    }

    public record ConfirmationResult(boolean accepted, Long confirmationId, List<Long> taskIds, String reply) {
        static ConfirmationResult rejected(String reply) {
            return new ConfirmationResult(false, null, List.of(), reply);
        }
    }
}
