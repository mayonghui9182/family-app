package com.familyapp.wechat.conversation;

import com.familyapp.wechat.conversation.WechatConversationCommandParser.CommandType;
import com.familyapp.wechat.conversation.WechatConversationTurnPreparationService.PreparedTurn;
import com.familyapp.wechat.conversation.WechatFamilyRoutingService.FamilyOption;
import com.familyapp.wechat.conversation.WechatFamilyRoutingService.RouteDecision;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 确定性命令的本地执行器。
 *
 * <p>该类只调用 backend 内部状态服务，不调用 agent-service 或微信 HTTP。
 * 精确确认调用专用确认事务服务；历史删除等待独立二次确认流程，均不进入 AI。</p>
 */
@Service
public class WechatConversationLocalCommandHandler {

    private final WechatFamilySelectionCoordinator familySelectionCoordinator;
    private final WechatFamilyRoutingService familyRoutingService;
    private final WechatChatContextRepository contextRepository;
    private final WechatConversationStateService stateService;
    private final WechatTaskConfirmationService confirmationService;
    private final WechatConversationHistoryService history;
    private final FamilyTaskExperienceService experienceService;

    public WechatConversationLocalCommandHandler(
            WechatFamilySelectionCoordinator familySelectionCoordinator,
            WechatFamilyRoutingService familyRoutingService,
            WechatChatContextRepository contextRepository,
            WechatConversationStateService stateService,
            WechatTaskConfirmationService confirmationService,
            WechatConversationHistoryService history,
            FamilyTaskExperienceService experienceService) {
        this.familySelectionCoordinator = familySelectionCoordinator;
        this.familyRoutingService = familyRoutingService;
        this.contextRepository = contextRepository;
        this.stateService = stateService;
        this.confirmationService = confirmationService;
        this.history = history;
        this.experienceService = experienceService;
    }

    @Transactional
    public LocalCommandResult handle(PreparedTurn turn, Instant now) {
        if (turn.immediateReply() != null) {
            return result(turn, turn.immediateReply());
        }
        if (turn.routingAction() != null) {
            return result(turn, routingReply(turn.routingAction()));
        }

        return switch (turn.command().type()) {
            case DEFAULT_FAMILY -> defaultFamily(turn, false);
            case SET_DEFAULT_FAMILY -> defaultFamily(turn, true);
            case LIST_FAMILIES -> listFamilies(turn, now);
            case CURRENT_FAMILY -> result(
                    turn,
                    turn.familyName() == null
                            ? "还没有选择家庭，请发送“切换家庭”。"
                            : "当前家庭是“" + turn.familyName() + "”。");
            case SELECT_FAMILY -> selectFamily(turn, now);
            case NEW_TASK -> startNewConversation(turn);
            case CURRENT_SUMMARY -> result(
                    turn,
                    turn.rollingSummary() == null || turn.rollingSummary().isBlank()
                            ? "当前会话还没有可展示的摘要。"
                            : "当前摘要：" + turn.rollingSummary());
            case CANCEL_CONVERSATION -> cancelConversation(turn);
            case LIST_HISTORY -> result(turn, history.list(turn.lease().wechatBindId(),
                    requirePositive(turn.userId(), "user_id"), requirePositive(turn.familyId(), "family_id"), now));
            case CONTINUE_HISTORY -> continueHistory(turn, now);
            case DELETE_HISTORY, CLEAR_HISTORY -> deleteHistory(turn, now, false);
            case CONFIRM_DELETE_HISTORY, CONFIRM_CLEAR_HISTORY -> deleteHistory(turn, now, true);
            case CONFIRM_CREATE -> confirmTasks(turn, now);
            case SET_TASK_PREFERENCE -> result(turn, experienceService.setExplicit(
                    turn.lease(), requirePositive(turn.userId(), "user_id"),
                    requirePositive(turn.familyId(), "family_id"), now));
            case ORDINARY_TEXT -> throw new IllegalArgumentException(
                    "普通任务文本不能进入本地命令处理器");
        };
    }

    private LocalCommandResult confirmTasks(PreparedTurn turn, Instant now) {
        if (turn.familyId() == null || turn.conversationId() == null) {
            return result(turn, "当前没有待确认候选，请先告诉我需要安排什么。");
        }
        var confirmation = confirmationService.confirm(turn.lease(),
                requirePositive(turn.userId(), "user_id"), turn.familyId(), turn.conversationId(), now);
        return new LocalCommandResult(confirmation.reply(), turn.familyId(),
                turn.conversationId(), confirmation.confirmationId());
    }

    private LocalCommandResult defaultFamily(PreparedTurn turn, boolean setting) {
        var context = contextRepository.findByWechatBindIdForUpdate(turn.lease().wechatBindId())
                .orElseThrow(WechatFamilyAccessDeniedException::new);
        if (!java.util.Objects.equals(context.getUserId(), turn.userId())) throw new WechatFamilyAccessDeniedException();
        if (setting) {
            var selected = familyRoutingService.uniqueFamily(turn.lease().wechatBindId(), turn.command().value());
            if (selected.isEmpty()) return result(turn, "家庭名称无效或有重名，请发送完整且唯一的家庭名称。");
            context.setDefaultFamilyId(selected.get().familyId());
            contextRepository.save(context);
            return result(turn, "默认家庭已设置为“" + selected.get().name() + "”。当前家庭不变。");
        }
        var fallback = familyRoutingService.automaticSelection(turn.lease().wechatBindId(),
                null, context.getDefaultFamilyId());
        if (fallback.selected() == null) return result(turn, "当前没有可用的默认家庭，请先加入家庭。");
        return result(turn, "默认家庭是“" + fallback.selected().name() + "”。可发送“设置默认家庭 家庭名”修改。");
    }

    private LocalCommandResult deleteHistory(PreparedTurn turn, Instant now, boolean confirming) {
        long familyId = requirePositive(turn.familyId(), "family_id");
        long userId = requirePositive(turn.userId(), "user_id");
        String reply;
        try {
            reply = confirming ? history.confirmDelete(turn.lease().wechatBindId(), userId, familyId,
                    turn.command().number(), turn.lease().messageId(), now)
                    : history.requestDelete(turn.lease().wechatBindId(), userId, familyId, turn.command().number(), now);
        } catch (IllegalArgumentException exception) {
            reply = "历史编号已失效，请重新发送“历史会话”。";
        }
        // 删除后不能把回执关联已隐藏会话，完成器将选择当前家庭的有效会话。
        return new LocalCommandResult(reply, familyId, null);
    }

    private LocalCommandResult listFamilies(PreparedTurn turn, Instant now) {
        RouteDecision decision = familyRoutingService.beginSelection(
                turn.lease().wechatBindId(), 0L, now);
        return result(turn, familyOptionsReply(decision.options()));
    }

    private LocalCommandResult selectFamily(PreparedTurn turn, Instant now) {
        WechatChatContext context = contextRepository
                .findByWechatBindIdForUpdate(turn.lease().wechatBindId())
                .orElseThrow(() -> new IllegalStateException("微信绑定上下文不存在"));
        if (context.getPendingFamilySelection() != null) {
            var action = familySelectionCoordinator.applySelection(
                    turn.lease().wechatBindId(),
                    turn.lease().messageId(),
                    turn.command().value());
            return result(turn, routingReply(action));
        }

        RouteDecision decision = familyRoutingService.beginSelection(
                turn.lease().wechatBindId(),
                context.getRouteRevision() == null ? 0L : context.getRouteRevision(),
                now);
        String expected = normalize(turn.command().value());
        List<FamilyOption> matches = decision.options().stream()
                .filter(option -> normalize(option.name()).equals(expected))
                .toList();
        if (matches.size() != 1) {
            return result(turn, familyOptionsReply(decision.options()));
        }

        FamilyOption selected = matches.get(0);
        long userId = requirePositive(turn.userId(), "user_id");
        Long currentFamilyId = context.getCurrentFamilyId();
        WechatTaskConversation conversation;
        if (currentFamilyId == null) {
            conversation = stateService.openOrResume(
                    turn.lease().wechatBindId(), userId, selected.familyId());
        } else {
            conversation = stateService.switchFamily(
                    turn.lease().wechatBindId(),
                    userId,
                    currentFamilyId,
                    selected.familyId());
        }
        context.setCurrentFamilyId(selected.familyId());
        context.setPendingFamilySelection(null);
        context.setRouteRevision(
                (context.getRouteRevision() == null ? 0L : context.getRouteRevision()) + 1);
        contextRepository.save(context);
        return new LocalCommandResult(
                "已切换到“" + selected.name() + "”。",
                selected.familyId(),
                conversation.getId());
    }

    private LocalCommandResult startNewConversation(PreparedTurn turn) {
        long familyId = requirePositive(turn.familyId(), "family_id");
        long userId = requirePositive(turn.userId(), "user_id");
        WechatTaskConversation conversation = stateService.startNewConversation(
                turn.lease().wechatBindId(), userId, familyId);
        return new LocalCommandResult(
                "已开始新的任务会话，请告诉我需要安排什么。",
                familyId,
                conversation.getId());
    }

    private LocalCommandResult cancelConversation(PreparedTurn turn) {
        if (turn.conversationId() == null || turn.familyId() == null) {
            return result(turn, "当前没有可取消的任务会话。");
        }
        stateService.cancel(turn.conversationId(), turn.familyId());
        return result(turn, "当前任务会话已取消。");
    }

    private LocalCommandResult continueHistory(PreparedTurn turn, Instant now) {
        long familyId = requirePositive(turn.familyId(), "family_id");
        long userId = requirePositive(turn.userId(), "user_id");
        try {
            var child = history.continueHistory(turn.lease().wechatBindId(), userId, familyId,
                    turn.command().number(), turn.lease().messageId(), now);
            return new LocalCommandResult("已开始新的派生会话。请补充这次要安排什么，旧任务不会再次创建。",
                    familyId, child.getId());
        } catch (IllegalArgumentException exception) {
            return result(turn, "历史编号已失效，请重新发送“历史会话”。");
        }
    }

    private String routingReply(
            WechatFamilySelectionCoordinator.RoutingAction action) {
        return switch (action.type()) {
            case CHALLENGE -> familyOptionsReply(action.options());
            case NO_FAMILY -> "当前账号没有可用家庭，请先在应用中加入或创建家庭。";
            case REPLAY, ROUTED -> "家庭已选择，原消息会继续处理，不需要重新发送。";
            case REJECTED -> "没有识别到有效家庭，请按列表回复编号或完整家庭名称。";
            case NO_CHALLENGE -> "当前没有待处理的家庭选择，请发送“切换家庭”。";
            case ALREADY_HANDLED -> "这条消息已经处理过了。";
        };
    }

    private String familyOptionsReply(List<FamilyOption> options) {
        if (options.isEmpty()) {
            return "当前账号没有可用家庭，请先在应用中加入或创建家庭。";
        }
        if (options.size() == 1) {
            return "当前可用家庭是“" + options.get(0).name() + "”。";
        }
        StringBuilder reply = new StringBuilder("请选择家庭，可发送“切换到家庭名”：");
        for (int index = 0; index < options.size(); index++) {
            FamilyOption option = options.get(index);
            reply.append("\n")
                    .append(index + 1)
                    .append(". ")
                    .append(option.name())
                    .append("（")
                    .append(option.roleName())
                    .append("）");
        }
        return reply.toString();
    }

    private LocalCommandResult result(PreparedTurn turn, String reply) {
        return new LocalCommandResult(
                reply, turn.familyId(), turn.conversationId());
    }

    private String normalize(String value) {
        return Normalizer.normalize(
                        value == null ? "" : value,
                        Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private long requirePositive(Long value, String field) {
        if (value == null || value < 1) {
            throw new IllegalStateException(field + " 缺失");
        }
        return value;
    }

    public record LocalCommandResult(
            String reply,
            Long familyId,
            Long conversationId,
            Long confirmationId) {
        public LocalCommandResult(String reply, Long familyId, Long conversationId) {
            this(reply, familyId, conversationId, null);
        }
    }
}
