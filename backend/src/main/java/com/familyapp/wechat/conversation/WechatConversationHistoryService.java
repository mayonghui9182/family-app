package com.familyapp.wechat.conversation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 编号固定在最近一次家庭历史快照，避免列表排序变化导致继续或删除错误会话。 */
@Service
public class WechatConversationHistoryService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private final WechatChatContextRepository contexts;
    private final WechatConversationStateService state;
    private final WechatTaskConversationRepository conversations;
    private final WechatConversationMessageRepository messages;
    private final WechatFamilyAccessGuard access;

    public WechatConversationHistoryService(WechatChatContextRepository contexts,
            WechatConversationStateService state, WechatTaskConversationRepository conversations,
            WechatConversationMessageRepository messages, WechatFamilyAccessGuard access) {
        this.contexts = contexts;
        this.state = state;
        this.conversations = conversations;
        this.messages = messages;
        this.access = access;
    }

    @Transactional
    public String list(long bindId, long userId, long familyId, Instant now) {
        var context = context(bindId, userId, familyId);
        var history = state.listHistory(bindId, userId, familyId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("family_id", familyId);
        view.put("route_revision", context.getRouteRevision());
        view.put("expires_at", now.plusSeconds(300).toString());
        view.put("conversation_ids", history.stream().map(WechatTaskConversation::getId).toList());
        // 历史编号也是删除挑战的定位依据，与删除挑战共用 JSON 容器但使用独立字段。
        context.setPendingHistoryDelete(Map.of("history_view", view));
        contexts.save(context);
        if (history.isEmpty()) return "当前家庭还没有历史任务会话。";
        StringBuilder reply = new StringBuilder("当前家庭最近会话（编号五分钟内有效）：");
        for (int index = 0; index < history.size(); index++) {
            var item = history.get(index);
            String summary = item.getRollingSummary();
            if (summary == null || summary.isBlank()) summary = "暂无摘要";
            if (summary.length() > 120) summary = summary.substring(0, 120) + "…";
            reply.append("\n").append(index + 1).append(". ").append(TIME.format(item.getLastActivityAt()))
                    .append(" ").append(statusName(item.getStatus())).append("：").append(summary);
        }
        return reply.append("\n回复“继续 N”开始新的派生会话；旧任务不会再次创建。").toString();
    }

    @Transactional
    public WechatTaskConversation continueHistory(long bindId, long userId, long familyId,
            int number, long sourceMessageId, Instant now) {
        var context = context(bindId, userId, familyId);
        Long parentId = resolve(context, number, now);
        var source = messages.findByIdAndFamilyId(sourceMessageId, familyId)
                .orElseThrow(WechatFamilyAccessDeniedException::new);
        if (!Objects.equals(source.getWechatBindId(), bindId) || !Objects.equals(source.getUserId(), userId)) {
            throw new WechatFamilyAccessDeniedException();
        }
        if (source.getConversationId() != null) {
            var already = conversations.findByIdAndFamilyId(source.getConversationId(), familyId).orElse(null);
            if (already != null && Objects.equals(already.getParentConversationId(), parentId)) return already;
        }
        var child = state.deriveHistory(bindId, userId, familyId, parentId);
        source.setConversationId(child.getId());
        messages.save(source);
        return child;
    }

    private Long resolve(WechatChatContext context, int number, Instant now) {
        var stored = context.getPendingHistoryDelete();
        if (stored == null || !(stored.get("history_view") instanceof Map<?, ?> view)
                || !sameNumber(view.get("family_id"), context.getCurrentFamilyId())
                || !sameNumber(view.get("route_revision"), context.getRouteRevision())
                || !(view.get("expires_at") instanceof String expiry)
                || !Instant.parse(expiry).isAfter(now)
                || !(view.get("conversation_ids") instanceof List<?> ids)
                || number < 1 || number > ids.size() || !(ids.get(number - 1) instanceof Number id)) {
            throw new IllegalArgumentException("历史编号已失效，请重新发送“历史会话”。");
        }
        return id.longValue();
    }

    @Transactional
    public String requestDelete(long bindId, long userId, long familyId, Integer number, Instant now) {
        var context = context(bindId, userId, familyId);
        List<Long> ids;
        if (number == null) {
            ids = conversations.history(familyId, bindId, userId,
                    org.springframework.data.domain.Pageable.unpaged()).stream()
                    .map(WechatTaskConversation::getId).toList();
        } else {
            Long id = resolve(context, number, now);
            var selected = conversations.findByIdAndFamilyIdForUpdate(id, familyId)
                    .orElseThrow(WechatFamilyAccessDeniedException::new);
            requireHistoryOwner(selected, bindId, userId);
            ids = List.of(id);
        }
        if (ids.isEmpty()) return "当前家庭没有可删除的历史会话。";
        Map<String, Object> challenge = new LinkedHashMap<>();
        challenge.put("family_id", familyId); challenge.put("route_revision", context.getRouteRevision());
        challenge.put("expires_at", now.plusSeconds(300).toString());
        challenge.put("operation", number == null ? "CLEAR" : "DELETE");
        if (number != null) challenge.put("number", number);
        challenge.put("conversation_ids", ids);
        Map<String, Object> stored = new LinkedHashMap<>();
        if (context.getPendingHistoryDelete() != null) stored.putAll(context.getPendingHistoryDelete());
        stored.put("challenge", challenge);
        context.setPendingHistoryDelete(stored);
        contexts.save(context);
        return "将删除当前家庭 " + ids.size() + " 条历史会话，不删除已创建任务。五分钟内回复“"
                + (number == null ? "确认清空" : "确认删除 " + number) + "”后才执行。";
    }

    @Transactional
    public String confirmDelete(long bindId, long userId, long familyId, Integer number,
            long sourceMessageId, Instant now) {
        var context = context(bindId, userId, familyId);
        var stored = context.getPendingHistoryDelete();
        if (stored != null && sameNumber(stored.get("completed_message_id"), sourceMessageId)
                && sameNumber(stored.get("family_id"), familyId)
                && sameNumber(stored.get("route_revision"), context.getRouteRevision())) {
            return "历史会话已删除，已创建任务保持不变。";
        }
        if (stored == null || !(stored.get("challenge") instanceof Map<?, ?> challenge)
                || !sameNumber(challenge.get("family_id"), familyId)
                || !sameNumber(challenge.get("route_revision"), context.getRouteRevision())
                || !(challenge.get("expires_at") instanceof String expiry)
                || !Instant.parse(expiry).isAfter(now)
                || !Objects.equals(challenge.get("operation"), number == null ? "CLEAR" : "DELETE")
                || (number != null && !sameNumber(challenge.get("number"), number.longValue()))
                || !(challenge.get("conversation_ids") instanceof List<?> ids)) {
            return "删除确认已失效，请重新发送删除指令；历史未删除。";
        }
        var source = messages.findByIdAndFamilyId(sourceMessageId, familyId)
                .orElseThrow(WechatFamilyAccessDeniedException::new);
        var parsed = new WechatConversationCommandParser().parse(source.getContent());
        if (!Objects.equals(source.getWechatBindId(), bindId) || !Objects.equals(source.getUserId(), userId)
                || parsed.type() != (number == null ? WechatConversationCommandParser.CommandType.CONFIRM_CLEAR_HISTORY
                        : WechatConversationCommandParser.CommandType.CONFIRM_DELETE_HISTORY)
                || !Objects.equals(parsed.number(), number)) throw new WechatFamilyAccessDeniedException();
        for (Object raw : ids) {
            if (!(raw instanceof Number id)) throw new IllegalArgumentException("历史删除快照无效");
            var item = conversations.findByIdAndFamilyIdForUpdate(id.longValue(), familyId).orElse(null);
            if (item == null) continue;
            requireHistoryOwner(item, bindId, userId);
            if (item.getStatus() == ConversationStatus.PAUSED) {
                item.setStatus(ConversationStatus.CANCELLED);
                item.setPausedFromStatus(null); item.setActiveSlot(null);
            }
            item.setDeletedAt(now);
            conversations.save(item);
        }
        // 只保留无正文的完成回执键；原历史正文交由保留期清理作业处理。
        context.setPendingHistoryDelete(Map.of("completed_message_id", sourceMessageId,
                "family_id", familyId, "route_revision", context.getRouteRevision()));
        contexts.save(context);
        return "历史会话已删除，已创建任务保持不变。";
    }

    private void requireHistoryOwner(WechatTaskConversation item, long bindId, long userId) {
        if (!Objects.equals(item.getWechatBindId(), bindId) || !Objects.equals(item.getUserId(), userId)
                || (item.getStatus().isRecoverable() && item.getStatus() != ConversationStatus.PAUSED)) {
            throw new WechatFamilyAccessDeniedException();
        }
    }

    private boolean sameNumber(Object value, Long expected) {
        return value instanceof Number number && expected != null && number.longValue() == expected;
    }

    private WechatChatContext context(long bindId, long userId, long familyId) {
        var context = contexts.findByWechatBindIdForUpdate(bindId)
                .orElseThrow(WechatFamilyAccessDeniedException::new);
        if (!Objects.equals(context.getUserId(), userId)
                || !Objects.equals(context.getCurrentFamilyId(), familyId)) throw new WechatFamilyAccessDeniedException();
        access.requireAccess(userId, familyId, WechatFamilyAccessGuard.AccessOperation.READ_HISTORY);
        return context;
    }

    private String statusName(ConversationStatus status) {
        return switch (status) {
            case PAUSED -> "已暂停";
            case CONFIRMED -> "已创建任务";
            case CANCELLED -> "已取消";
            case EXPIRED -> "已超时";
            default -> throw new IllegalArgumentException("活动会话不是历史条目");
        };
    }
}
