package com.familyapp.wechat.conversation;

import com.familyapp.category.CategoryRepository;
import com.familyapp.profile.PersonProfileRepository;
import com.familyapp.task.TaskPriority;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.CandidateSnapshot;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.CandidateTask;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.CategoryReference;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.ExperienceContext;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.FamilyContext;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.MemberReference;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.RecentMessage;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.SlotSource;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.SlotValue;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnRequest;
import com.familyapp.wechat.conversation.WechatConversationCommandParser.ParseContext;
import com.familyapp.wechat.conversation.WechatConversationCommandParser.ParsedCommand;
import com.familyapp.wechat.conversation.WechatConversationLeaseService.MessageLease;
import com.familyapp.wechat.conversation.WechatFamilyAccessGuard.FamilyAccess;
import com.familyapp.wechat.conversation.WechatFamilySelectionCoordinator.RoutingAction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在短事务内把已领取消息准备成本地命令或受控 AI 请求。
 *
 * <p>该服务只读取和更新数据库，不执行任何 HTTP；返回后事务已经提交。</p>
 */
@Service
public class WechatConversationTurnPreparationService {

    static final List<String> ALLOWED_SLOT_KEYS = List.of(
            "topic",
            "title",
            "description",
            "due_date",
            "priority",
            "category_id",
            "category_name",
            "assignee_profile_id",
            "recurrence",
            "notes");

    private static final int MAX_MODEL_TEXT_LENGTH = 2_000;
    private static final String LONG_INPUT_REPLY =
            "这段内容有点长，请分成两到三条消息发送，我会继续帮你整理。";

    private final WechatConversationMessageRepository messageRepository;
    private final WechatChatContextRepository contextRepository;
    private final WechatTaskConversationRepository conversationRepository;
    private final WechatCandidateVersionRepository candidateRepository;
    private final FamilyTaskExperienceSelectionService experienceSelection;
    private final CategoryRepository categoryRepository;
    private final PersonProfileRepository profileRepository;
    private final WechatConversationCommandParser commandParser;
    private final WechatFamilySelectionCoordinator familySelectionCoordinator;
    private final WechatConversationStateService stateService;
    private final WechatFamilyAccessGuard accessGuard;
    private final ObjectMapper objectMapper;

    public WechatConversationTurnPreparationService(
            WechatConversationMessageRepository messageRepository,
            WechatChatContextRepository contextRepository,
            WechatTaskConversationRepository conversationRepository,
            WechatCandidateVersionRepository candidateRepository,
            FamilyTaskExperienceSelectionService experienceSelection,
            CategoryRepository categoryRepository,
            PersonProfileRepository profileRepository,
            WechatConversationCommandParser commandParser,
            WechatFamilySelectionCoordinator familySelectionCoordinator,
            WechatConversationStateService stateService,
            WechatFamilyAccessGuard accessGuard,
            ObjectMapper objectMapper) {
        this.messageRepository = messageRepository;
        this.contextRepository = contextRepository;
        this.conversationRepository = conversationRepository;
        this.candidateRepository = candidateRepository;
        this.experienceSelection = experienceSelection;
        this.categoryRepository = categoryRepository;
        this.profileRepository = profileRepository;
        this.commandParser = commandParser;
        this.familySelectionCoordinator = familySelectionCoordinator;
        this.stateService = stateService;
        this.accessGuard = accessGuard;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PreparedTurn prepare(MessageLease lease, Instant now) {
        WechatConversationMessage message = messageRepository
                .findByIdAndWechatBindId(lease.messageId(), lease.wechatBindId())
                .orElseThrow(() -> new IllegalStateException("已领取消息不存在"));
        requireOwnedMessage(message, lease, now);

        WechatChatContext context = contextRepository
                .findByWechatBindId(lease.wechatBindId())
                .orElseThrow(() -> new IllegalStateException("微信绑定上下文不存在"));
        requireOwnedContext(context, lease, now);

        if (context.getCurrentFamilyId() != null
                && accessGuard.findAccess(context.getUserId(), context.getCurrentFamilyId()).isEmpty()) {
            // 终止无权家庭的活动会话；新未路由消息随后按默认规则选有效家庭。
            stateService.invalidateIfAccessRevoked(lease.wechatBindId(), context.getUserId(),
                    context.getCurrentFamilyId());
            context.setCurrentFamilyId(null);
            context.setPendingFamilySelection(null);
            context.setPendingHistoryDelete(null);
            context.setRouteRevision((context.getRouteRevision() == null ? 0L : context.getRouteRevision()) + 1);
            contextRepository.save(context);
        }

        boolean awaitingFamily = context.getPendingFamilySelection() != null;
        ParsedCommand command = commandParser.parse(
                message.getContent(),
                awaitingFamily
                        ? ParseContext.waitingForFamilySelection()
                        : ParseContext.withoutPendingFamilySelection());

        if (awaitingFamily) {
            return PreparedTurn.local(
                    lease, command, null, message, context, null, null);
        }

        RoutingAction routingAction = null;
        if (message.getFamilyId() == null) {
            routingAction = familySelectionCoordinator.routeMessage(
                    lease.wechatBindId(), lease.messageId());
            if (routingAction.type()
                    != WechatFamilySelectionCoordinator.ActionType.ROUTED) {
                return PreparedTurn.local(
                        lease, command, routingAction, message, context, null, null);
            }
            // 家庭已确定后继续执行原命令，不能让 ROUTED 回执吞掉创建确认。
            routingAction = null;
            message = messageRepository
                    .findByIdAndWechatBindId(lease.messageId(), lease.wechatBindId())
                    .orElseThrow(() -> new IllegalStateException("路由后的消息不存在"));
            context = contextRepository.findByWechatBindId(lease.wechatBindId())
                    .orElseThrow(() -> new IllegalStateException("路由后的上下文不存在"));
        }

        long familyId = requirePositive(message.getFamilyId(), "family_id");
        long userId = requirePositive(message.getUserId(), "user_id");
        FamilyAccess access = accessGuard.requireAccess(
                userId,
                familyId,
                WechatFamilyAccessGuard.AccessOperation.ROUTE_MESSAGE);

        command = commandParser.parse(message.getContent());
        WechatTaskConversation existing = conversationRepository
                .findByWechatBindIdAndFamilyIdAndActiveSlot(
                        lease.wechatBindId(), familyId, (short) 1)
                .orElse(null);
        if (command.type() == WechatConversationCommandParser.CommandType.CONFIRM_CREATE) {
            if (message.getConversationId() != null) {
                // 已准备或已提交的消息重试始终定位原会话，不能因出现新会话而改写目标。
                existing = conversationRepository.findByIdAndFamilyId(message.getConversationId(), familyId)
                        .filter(item -> item.getDeletedAt() == null)
                        .filter(item -> item.getWechatBindId().equals(lease.wechatBindId()))
                        .filter(item -> item.getUserId().equals(userId))
                        .orElse(null);
            } else if (existing == null) {
                existing = conversationRepository
                        .findFirstByWechatBindIdAndFamilyIdAndDeletedAtIsNullOrderByIdDesc(
                                lease.wechatBindId(), familyId)
                        .filter(item -> item.getStatus() == ConversationStatus.CONFIRMED)
                        .filter(item -> item.getUserId().equals(userId))
                        .orElse(null);
            }
            if (existing != null) {
                message.setConversationId(existing.getId());
                messageRepository.save(message);
            }
        }
        if (command.type()
                != WechatConversationCommandParser.CommandType.ORDINARY_TEXT) {
            return PreparedTurn.local(
                    lease, command, routingAction, message, context, existing, access);
        }

        if (isCategoryQuery(message.getContent())) {
            return PreparedTurn.localReply(lease, message, context, existing, access,
                    categoryReply(familyId, existing, now));
        }

        if (message.getContent() == null
                || message.getContent().isBlank()
                || message.getContent().length() > MAX_MODEL_TEXT_LENGTH) {
            return PreparedTurn.localReply(
                    lease, message, context, existing, access, LONG_INPUT_REPLY);
        }

        WechatTaskConversation conversation = stateService.openOrResume(
                lease.wechatBindId(), userId, familyId);
        message.setConversationId(conversation.getId());
        messageRepository.save(message);

        TurnRequest request = buildRequest(message, context, conversation, access);
        return PreparedTurn.ai(
                lease, command, message, context, conversation, access, request);
    }

    /** 分类目录属于当前家庭的确定性数据，查询不需要调用模型或修改候选。 */
    private boolean isCategoryQuery(String content) {
        if (content == null) return false;
        String text = content.strip().replaceAll("[？?。！!]+$", "");
        return java.util.Set.of("现有分类都啥", "现有分类都有啥", "现有分类有哪些", "当前分类有哪些",
                "分类有哪些", "有哪些分类", "查看分类", "分类列表", "现有分类").contains(text);
    }

    private String categoryReply(long familyId, WechatTaskConversation conversation, Instant now) {
        var categories = categoryRepository.findByFamilyIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(familyId);
        StringBuilder reply = new StringBuilder();
        if (categories.isEmpty()) {
            reply.append("当前家庭还没有可选分类。可以先保持分类未指定；需要分类时请在家庭应用中添加。");
        } else {
            reply.append("当前家庭可选分类：\n");
            for (int index = 0; index < Math.min(8, categories.size()); index++) {
                reply.append(index + 1).append(". ").append(categories.get(index).getName()
                        .replaceAll("\\s+", " ")).append("\n");
            }
            if (categories.size() > 8) reply.append("其余分类请在家庭应用中查看。\n");
            reply.append("如需选择，请回复“分类 ”加上分类名称。");
        }
        if (conversation != null && conversation.getCurrentRevision() > 0
                && (conversation.getStatus() == ConversationStatus.ACTIVE
                    || conversation.getStatus() == ConversationStatus.AWAITING_CONFIRMATION)
                && conversation.getExpiresAt() != null && conversation.getExpiresAt().isAfter(now)) {
            reply.append("\n候选第 ").append(conversation.getCurrentRevision())
                    .append(" 版仍保留。按该版创建请核对原清单，回复“确认创建 ")
                    .append(conversation.getCurrentRevision()).append("”。");
        }
        return reply.toString();
    }

    private TurnRequest buildRequest(
            WechatConversationMessage message,
            WechatChatContext context,
            WechatTaskConversation conversation,
            FamilyAccess access) {
        List<ExperienceContext> experiences = experienceSelection
                .select(conversation.getFamilyId(), message.getContent() + " " + conversation.getSlotState()
                        + " " + currentCandidate(conversation))
                .stream()
                .limit(WechatConversationAgentClient.MAX_EXPERIENCES)
                .map(this::experience)
                .toList();

        return new TurnRequest(
                conversation.getId(),
                context.getRouteRevision() == null ? 0L : context.getRouteRevision(),
                conversation.getCurrentRevision(),
                message.getId(),
                message.getContent(),
                new FamilyContext(
                        conversation.getFamilyId(),
                        access.familyName(),
                        conversation.getUserId(),
                        access.role()),
                ALLOWED_SLOT_KEYS,
                slotState(conversation.getSlotState()),
                currentCandidate(conversation),
                conversation.getRollingSummary(),
                recentMessages(conversation),
                experiences,
                categoryRepository
                        .findByFamilyIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(
                                conversation.getFamilyId())
                        .stream()
                        .limit(200)
                        .map(category -> new CategoryReference(
                                category.getId(), category.getName()))
                        .toList(),
                profileRepository
                        .findByFamilyIdAndDeletedAtIsNull(conversation.getFamilyId())
                        .stream()
                        .limit(200)
                        .map(profile -> new MemberReference(
                                profile.getId(), profile.getName(), profile.getUserId()))
                        .toList(),
                message.getReceivedAt());
    }

    private CandidateSnapshot currentCandidate(WechatTaskConversation conversation) {
        if (conversation.getCurrentRevision() == null
                || conversation.getCurrentRevision() < 1) {
            return null;
        }
        return candidateRepository
                .findByConversationIdAndFamilyIdAndRevision(
                        conversation.getId(),
                        conversation.getFamilyId(),
                        conversation.getCurrentRevision())
                .map(candidate -> new CandidateSnapshot(
                        candidate.getRevision(),
                        candidate.getCandidatePayload().stream()
                                .map(this::candidateTask)
                                .toList()))
                .orElse(null);
    }

    private CandidateTask candidateTask(Map<String, Object> value) {
        return new CandidateTask(
                text(value.get("title")),
                nullableText(value.get("description")),
                localDate(value.get("due_date")),
                priority(value.get("priority")),
                number(value.get("category_id")),
                number(value.get("assignee_profile_id")),
                value.get("category_name") == null ? null : text(value.get("category_name")));
    }

    private List<RecentMessage> recentMessages(
            WechatTaskConversation conversation) {
        List<WechatConversationMessage> latest = messageRepository
                .findTop16ByFamilyIdAndConversationIdOrderByIdDesc(
                        conversation.getFamilyId(), conversation.getId());
        List<WechatConversationMessage> selected = new ArrayList<>(
                latest.stream()
                        .filter(item -> item.getContent() != null
                                && !item.getContent().isBlank())
                        .limit(WechatConversationAgentClient.MAX_RECENT_MESSAGES)
                        .toList());
        Collections.reverse(selected);
        return selected.stream()
                .map(item -> new RecentMessage(
                        item.getId(),
                        item.getDirection(),
                        truncate(item.getContent(), MAX_MODEL_TEXT_LENGTH),
                        item.getCreatedAt()))
                .toList();
    }

    private Map<String, SlotValue> slotState(Map<String, Object> stored) {
        Map<String, SlotValue> result = new LinkedHashMap<>();
        if (stored == null || stored.isEmpty()) {
            return result;
        }
        for (String key : ALLOWED_SLOT_KEYS) {
            Object raw = stored.get(key);
            if (!(raw instanceof Map<?, ?> value)) {
                continue;
            }
            Object sourceValue = value.get("source");
            if (!(sourceValue instanceof String sourceText)) {
                continue;
            }
            try {
                SlotSource source = SlotSource.valueOf(sourceText);
                result.put(key, new SlotValue(
                        value.get("value"),
                        source,
                        number(value.get("source_message_id")),
                        number(value.get("experience_id"))));
            } catch (IllegalArgumentException ignored) {
                // 旧的无效槽位不进入模型上下文，后续写入仍由动作落库服务严格校验。
            }
        }
        return result;
    }

    private ExperienceContext experience(FamilyTaskExperience value) {
        return new ExperienceContext(
                value.getId(),
                value.getVersion(),
                value.getNormalizedKey(),
                truncate(json(java.util.Map.of("scope", value.getScopePayload(),
                        "defaults", value.getValuePayload())), 500));
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("家庭经验无法序列化", exception);
        }
    }

    private void requireOwnedMessage(
            WechatConversationMessage message,
            MessageLease lease,
            Instant now) {
        if (message.getProcessingStatus() != MessageProcessingStatus.PROCESSING
                || !lease.workerId().equals(message.getLeaseOwner())
                || message.getLeaseUntil() == null
                || !message.getLeaseUntil().isAfter(now)) {
            throw new IllegalStateException("消息租约已失效");
        }
    }

    private void requireOwnedContext(
            WechatChatContext context,
            MessageLease lease,
            Instant now) {
        if (!lease.workerId().equals(context.getLeaseOwner())
                || context.getLeaseUntil() == null
                || !context.getLeaseUntil().isAfter(now)) {
            throw new IllegalStateException("绑定租约已失效");
        }
    }

    private long requirePositive(Long value, String field) {
        if (value == null || value < 1) {
            throw new IllegalStateException(field + " 缺失");
        }
        return value;
    }

    private String text(Object value) {
        if (!(value instanceof String result) || result.isBlank()) {
            throw new IllegalStateException("候选标题无效");
        }
        return result;
    }

    private String nullableText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Long number(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    private LocalDate localDate(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private TaskPriority priority(Object value) {
        if (value == null) {
            return TaskPriority.MEDIUM;
        }
        return TaskPriority.valueOf(String.valueOf(value));
    }

    private String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    public record PreparedTurn(
            MessageLease lease,
            ParsedCommand command,
            RoutingAction routingAction,
            Long userId,
            Long familyId,
            String familyName,
            Long conversationId,
            String rollingSummary,
            TurnRequest turnRequest,
            ConversationStatus conversationStatus,
            String immediateReply) {

        static PreparedTurn local(
                MessageLease lease,
                ParsedCommand command,
                RoutingAction routingAction,
                WechatConversationMessage message,
                WechatChatContext context,
                WechatTaskConversation conversation,
                FamilyAccess access) {
            return new PreparedTurn(
                    lease,
                    command,
                    routingAction,
                    message.getUserId(),
                    message.getFamilyId(),
                    access == null ? null : access.familyName(),
                    conversation == null ? null : conversation.getId(),
                    conversation == null ? null : conversation.getRollingSummary(),
                    null,
                    conversation == null ? null : conversation.getStatus(),
                    null);
        }

        static PreparedTurn localReply(
                MessageLease lease,
                WechatConversationMessage message,
                WechatChatContext context,
                WechatTaskConversation conversation,
                FamilyAccess access,
                String reply) {
            return new PreparedTurn(
                    lease,
                    WechatConversationCommandParser.ParsedCommand.text(
                            message.getContent()),
                    null,
                    message.getUserId(),
                    message.getFamilyId(),
                    access.familyName(),
                    conversation == null ? null : conversation.getId(),
                    conversation == null ? null : conversation.getRollingSummary(),
                    null,
                    conversation == null ? null : conversation.getStatus(),
                    reply);
        }

        static PreparedTurn ai(
                MessageLease lease,
                ParsedCommand command,
                WechatConversationMessage message,
                WechatChatContext context,
                WechatTaskConversation conversation,
                FamilyAccess access,
                TurnRequest request) {
            return new PreparedTurn(
                    lease,
                    command,
                    null,
                    message.getUserId(),
                    message.getFamilyId(),
                    access.familyName(),
                    conversation.getId(),
                    conversation.getRollingSummary(),
                    request,
                    conversation.getStatus(),
                    null);
        }

        public boolean requiresAi() {
            return turnRequest != null;
        }
    }
}
