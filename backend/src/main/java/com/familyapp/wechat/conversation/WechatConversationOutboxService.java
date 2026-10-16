package com.familyapp.wechat.conversation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/** 会话锁内分配单调序号，消息历史与语义去重 outbox 在同一事务提交。 */
@Service
public class WechatConversationOutboxService {
    private final WechatTaskConversationRepository conversations;
    private final WechatConversationMessageRepository messages;
    private final WechatMessageOutboxRepository outbox;

    public WechatConversationOutboxService(WechatTaskConversationRepository conversations,
            WechatConversationMessageRepository messages, WechatMessageOutboxRepository outbox) {
        this.conversations = conversations;
        this.messages = messages;
        this.outbox = outbox;
    }

    @Transactional
    public WechatMessageOutbox enqueueReply(WechatConversationMessage source, Long familyId,
            Long conversationId, String key, String content, Instant notBefore) {
        if (key == null || key.isBlank() || key.length() > 200 || content == null || content.isBlank()) {
            throw new IllegalArgumentException("客服回复或去重键不符合约束");
        }
        var conversation = conversations.findByIdAndFamilyIdForUpdate(conversationId, familyId)
                .orElseThrow(WechatFamilyAccessDeniedException::new);
        if (!Objects.equals(conversation.getWechatBindId(), source.getWechatBindId())
                || !Objects.equals(conversation.getUserId(), source.getUserId())
                || !Objects.equals(source.getFamilyId(), familyId)) {
            throw new WechatFamilyAccessDeniedException();
        }
        var existing = outbox.findByConversationIdAndFamilyIdAndDedupeKey(conversationId, familyId, key);
        if (existing.isPresent()) return existing.get();
        var reply = messages.findByWechatAccountIdAndExternalMsgIdAndWechatBindIdAndFamilyIdAndConversationId(
                source.getWechatAccountId(), key, source.getWechatBindId(), familyId, conversationId)
                .orElseGet(() -> {
                    WechatConversationMessage message = new WechatConversationMessage();
                    message.setWechatAccountId(source.getWechatAccountId());
                    message.setExternalMsgId(key);
                    message.setOpenid(source.getOpenid());
                    message.setWechatBindId(source.getWechatBindId());
                    message.setUserId(source.getUserId());
                    message.setFamilyId(familyId);
                    message.setConversationId(conversationId);
                    message.setDirection(MessageDirection.OUTBOUND);
                    message.setContent(content);
                    message.setProcessingStatus(MessageProcessingStatus.PROCESSED);
                    message.setReceivedAt(notBefore);
                    message.setAvailableAt(notBefore);
                    message.setProcessedAt(notBefore);
                    return messages.save(message);
                });
        if (reply.getDirection() != MessageDirection.OUTBOUND || !content.equals(reply.getContent())) {
            throw new IllegalStateException("客服回复去重键已被不同业务消息占用");
        }
        return enqueueExisting(reply, key, notBefore);
    }

    @Transactional
    public WechatMessageOutbox enqueueExisting(WechatConversationMessage message, String key, Instant notBefore) {
        var conversation = conversations.findByIdAndFamilyIdForUpdate(
                message.getConversationId(), message.getFamilyId())
                .orElseThrow(WechatFamilyAccessDeniedException::new);
        if (message.getDirection() != MessageDirection.OUTBOUND
                || !Objects.equals(conversation.getUserId(), message.getUserId())
                || !Objects.equals(conversation.getWechatBindId(), message.getWechatBindId())) {
            throw new WechatFamilyAccessDeniedException();
        }
        var existing = outbox.findByConversationIdAndFamilyIdAndDedupeKey(
                message.getConversationId(), message.getFamilyId(), key);
        if (existing.isPresent()) return existing.get();
        WechatMessageOutbox queued = new WechatMessageOutbox();
        queued.setFamilyId(message.getFamilyId());
        queued.setConversationId(message.getConversationId());
        queued.setMessageId(message.getId());
        queued.setDedupeKey(key);
        queued.setSequenceNo(Math.addExact(outbox.lastSequence(message.getFamilyId(), message.getConversationId()), 1));
        queued.setNotBefore(notBefore);
        return outbox.save(queued);
    }

    @Transactional
    public void cancelProgress(Long familyId, Long conversationId, Long sourceMessageId) {
        conversations.findByIdAndFamilyIdForUpdate(conversationId, familyId)
                .orElseThrow(WechatFamilyAccessDeniedException::new);
        outbox.findByConversationIdAndFamilyIdAndDedupeKey(conversationId, familyId,
                "progress:" + sourceMessageId).ifPresent(found -> {
            // 与发送器争用同一行锁；已开始外呼的消息不能再被标记取消。
            var row = outbox.findForUpdate(found.getId(), familyId).orElseThrow();
            if (row.getStatus() == OutboxStatus.PENDING) {
                row.setStatus(OutboxStatus.CANCELLED);
                row.setNextRetryAt(null);
                outbox.save(row);
            }
        });
    }
}
