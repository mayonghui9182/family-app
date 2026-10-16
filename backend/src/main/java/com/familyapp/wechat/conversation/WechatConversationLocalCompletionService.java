package com.familyapp.wechat.conversation;

import com.familyapp.wechat.conversation.WechatConversationLeaseService.MessageLease;
import com.familyapp.wechat.conversation.WechatConversationLocalCommandHandler.LocalCommandResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 本地命令处理后的短事务提交器。
 *
 * <p>回复先写入不可变会话消息历史，后续 outbox 任务再负责实际发送微信。</p>
 */
@Service
public class WechatConversationLocalCompletionService {

    private final WechatConversationMessageRepository messageRepository;
    private final WechatChatContextRepository contextRepository;
    private final WechatConfirmationResultProjection confirmationProjection;
    private final WechatConversationOutboxService outbox;
    private final WechatTaskConversationRepository conversations;
    private final WechatConversationStateService state;
    private final WechatFamilyAccessGuard access;

    public WechatConversationLocalCompletionService(
            WechatConversationMessageRepository messageRepository,
            WechatChatContextRepository contextRepository,
            WechatConfirmationResultProjection confirmationProjection,
            WechatConversationOutboxService outbox,
            WechatTaskConversationRepository conversations,
            WechatConversationStateService state,
            WechatFamilyAccessGuard access) {
        this.messageRepository = messageRepository;
        this.contextRepository = contextRepository;
        this.confirmationProjection = confirmationProjection;
        this.outbox = outbox;
        this.conversations = conversations;
        this.state = state;
        this.access = access;
    }

    @Transactional
    public void complete(
            MessageLease lease,
            LocalCommandResult result,
            Instant now) {
        WechatConversationMessage source = messageRepository
                .findByIdAndWechatBindId(lease.messageId(), lease.wechatBindId())
                .orElseThrow(() -> new IllegalStateException("本地命令来源消息不存在"));

        if (!lease.workerId().equals(source.getLeaseOwner())) {
            throw new IllegalStateException("本地命令消息租约已失效");
        }

        if (result.confirmationId() != null) {
            confirmationProjection.project(result.familyId(), result.confirmationId(), now);
        } else {
            saveReply(source, result, now);
        }

        if (source.getProcessingStatus() == MessageProcessingStatus.PROCESSING) {
            int completed = messageRepository.completeProcessing(
                    lease.messageId(),
                    lease.wechatBindId(),
                    lease.workerId(),
                    now);
            if (completed == 0) {
                throw new IllegalStateException("本地命令完成状态提交冲突");
            }
        } else if (source.getProcessingStatus() == MessageProcessingStatus.WAITING_FAMILY
                || source.getProcessingStatus() == MessageProcessingStatus.PROCESSED) {
            source.setLeaseOwner(null);
            source.setLeaseUntil(null);
            if (source.getProcessingStatus() == MessageProcessingStatus.PROCESSED
                    && source.getProcessedAt() == null) {
                source.setProcessedAt(now);
            }
            messageRepository.save(source);
        } else {
            throw new IllegalStateException(
                    "本地命令来源消息状态不可完成: " + source.getProcessingStatus());
        }

        if (contextRepository.releaseLease(
                lease.wechatBindId(), lease.workerId(), now) == 0) {
            throw new IllegalStateException("绑定租约释放失败");
        }
    }

    private void saveReply(
            WechatConversationMessage source,
            LocalCommandResult result,
            Instant now) {
        if (result.reply() == null || result.reply().isBlank()) {
            throw new IllegalArgumentException("本地命令回复不能为空");
        }
        if (result.conversationId() != null && result.familyId() == null) {
            throw new IllegalArgumentException("会话回复缺少 family_id");
        }
        if (result.familyId() != null) {
            var context = contextRepository.findByWechatBindIdForUpdate(source.getWechatBindId())
                    .orElseThrow(WechatFamilyAccessDeniedException::new);
            if (!java.util.Objects.equals(context.getUserId(), source.getUserId())
                    || !java.util.Objects.equals(context.getCurrentFamilyId(), result.familyId())) {
                throw new WechatFamilyAccessDeniedException();
            }
            access.requireAccess(source.getUserId(), result.familyId(),
                    WechatFamilyAccessGuard.AccessOperation.READ_HISTORY);
            Long conversationId = result.conversationId();
            if (conversationId == null) {
                // 无活动会话时优先关联现有历史；首次进入有效家庭则建立真实的当前会话。
                conversationId = conversations.findFirstByWechatBindIdAndFamilyIdAndDeletedAtIsNullOrderByIdDesc(
                        source.getWechatBindId(), result.familyId()).map(WechatTaskConversation::getId)
                        .orElseGet(() -> state.openOrResume(source.getWechatBindId(),
                                source.getUserId(), result.familyId()).getId());
            }
            String key = "local-reply:" + source.getId();
            Long targetId = conversationId;
            var reply = messageRepository
                    .findByWechatAccountIdAndExternalMsgIdAndWechatBindIdAndFamilyIdAndConversationId(
                            source.getWechatAccountId(), key, source.getWechatBindId(), result.familyId(), targetId)
                    .orElseGet(() -> {
                        var value = new WechatConversationMessage();
                        value.setWechatAccountId(source.getWechatAccountId());
                        value.setExternalMsgId(key);
                        value.setOpenid(source.getOpenid());
                        value.setWechatBindId(source.getWechatBindId());
                        value.setUserId(source.getUserId());
                        value.setFamilyId(result.familyId());
                        value.setConversationId(targetId);
                        value.setDirection(MessageDirection.OUTBOUND);
                        value.setContent(result.reply());
                        value.setProcessingStatus(MessageProcessingStatus.PROCESSED);
                        value.setReceivedAt(now); value.setAvailableAt(now); value.setProcessedAt(now);
                        return messageRepository.save(value);
                    });
            outbox.enqueueExisting(reply, key, now);
            return;
        }

        WechatConversationMessage reply = new WechatConversationMessage();
        reply.setWechatAccountId(source.getWechatAccountId());
        reply.setOpenid(source.getOpenid());
        reply.setDirection(MessageDirection.OUTBOUND);
        reply.setContent(result.reply());
        reply.setWechatBindId(source.getWechatBindId());
        reply.setUserId(source.getUserId());
        reply.setFamilyId(result.familyId());
        reply.setConversationId(result.conversationId());
        reply.setProcessingStatus(MessageProcessingStatus.PROCESSED);
        reply.setAvailableAt(now);
        reply.setReceivedAt(now);
        reply.setProcessedAt(now);
        messageRepository.save(reply);
    }
}
