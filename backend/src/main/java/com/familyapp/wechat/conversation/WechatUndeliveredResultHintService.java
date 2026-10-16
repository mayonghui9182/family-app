package com.familyapp.wechat.conversation;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

/** 新有效入站可被动提示首次确认结果，不修改原 outbox 去重键或再次创建任务。 */
@Service
public class WechatUndeliveredResultHintService {
    private final WechatChatContextRepository contexts;
    private final WechatFamilyAccessGuard access;
    private final WechatTaskConfirmationRepository confirmations;
    private final WechatConfirmationTaskRepository links;
    private final WechatConversationCommandParser parser = new WechatConversationCommandParser();

    public WechatUndeliveredResultHintService(WechatChatContextRepository contexts,
            WechatFamilyAccessGuard access, WechatTaskConfirmationRepository confirmations,
            WechatConfirmationTaskRepository links) {
        this.contexts = contexts;
        this.access = access;
        this.confirmations = confirmations;
        this.links = links;
    }

    @Transactional(readOnly = true)
    public String hint(WechatConversationMessage message) {
        if (message.getWechatBindId() == null || message.getUserId() == null) return null;
        var command = parser.parse(message.getContent()).type();
        if (command != WechatConversationCommandParser.CommandType.ORDINARY_TEXT
                && command != WechatConversationCommandParser.CommandType.CONFIRM_CREATE) return null;
        var context = contexts.findByWechatBindId(message.getWechatBindId()).orElse(null);
        if (context == null || !Objects.equals(context.getUserId(), message.getUserId())
                || context.getCurrentFamilyId() == null
                || access.findAccess(message.getUserId(), context.getCurrentFamilyId()).isEmpty()) return null;
        Long familyId = context.getCurrentFamilyId();
        var results = confirmations.undeliveredResults(familyId, message.getUserId(),
                message.getWechatBindId(), PageRequest.of(0, 1));
        if (results.isEmpty()) return null;
        var committed = results.get(0);
        int count = links.findByConfirmationIdAndFamilyIdOrderByCandidateItemIndexAsc(committed.getId(), familyId).size();
        if (count == 0) return null;
        return "上次已成功创建 " + count + " 项任务，但结果消息尚未送达，可在家庭任务中查看。您的新消息已收到。";
    }
}
