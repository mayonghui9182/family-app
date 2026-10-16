package com.familyapp.wechat.conversation;

import com.familyapp.wechat.conversation.WechatConversationLeaseService.MessageLease;
import com.familyapp.wechat.conversation.WechatConversationTurnPreparationService.PreparedTurn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/** 仅发送粗粒度进度，不消费 token 流；重试沿用来源消息的语义键。 */
@Service
public class WechatConversationProgressService {
    private final WechatConversationProperties properties;
    private final WechatConversationMessageRepository messages;
    private final WechatConversationAiResponseFence fence;
    private final WechatConversationOutboxService outbox;

    public WechatConversationProgressService(WechatConversationProperties properties,
            WechatConversationMessageRepository messages, WechatConversationAiResponseFence fence,
            WechatConversationOutboxService outbox) {
        this.properties = properties;
        this.messages = messages;
        this.fence = fence;
        this.outbox = outbox;
    }

    @Transactional
    public void start(MessageLease lease, PreparedTurn turn, Instant now) {
        if (properties.getMaxReplySegments() < 3) return;
        var source = messages.findByIdAndFamilyId(lease.messageId(), turn.familyId())
                .orElseThrow(WechatFamilyAccessDeniedException::new);
        if (!fence.lockAndCheck(lease, turn, source, now).current()) return;
        outbox.enqueueReply(source, turn.familyId(), turn.conversationId(),
                "progress:" + source.getId(), "正在整理您的任务，请稍候。",
                now.plusMillis(properties.getProgressFastThresholdMs()));
    }

    @Transactional
    public void finish(PreparedTurn turn) {
        outbox.cancelProgress(turn.familyId(), turn.conversationId(), turn.lease().messageId());
    }
}
