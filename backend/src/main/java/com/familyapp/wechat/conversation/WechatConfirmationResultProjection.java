package com.familyapp.wechat.conversation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.Instant;

/** 依据已提交的确认凭据恢复结果消息，不执行任务写入，也不执行微信网络调用。 */
@Service
public class WechatConfirmationResultProjection {
    private final WechatTaskConfirmationRepository confirmations;
    private final WechatConfirmationTaskRepository links;
    private final WechatConversationMessageRepository messages;
    private final WechatConversationOutboxService outbox;
    private final WechatFamilyAccessGuard access;
    private final FamilyTaskExperienceProjection experiences;

    public WechatConfirmationResultProjection(WechatTaskConfirmationRepository confirmations,
            WechatConfirmationTaskRepository links, WechatConversationMessageRepository messages,
            WechatConversationOutboxService outbox, WechatFamilyAccessGuard access,
            FamilyTaskExperienceProjection experiences) {
        this.confirmations = confirmations;
        this.links = links;
        this.messages = messages;
        this.outbox = outbox;
        this.access = access;
        this.experiences = experiences;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void project(Long familyId, Long confirmationId, Instant now) {
        var confirmation = confirmations.findByIdAndFamilyId(confirmationId, familyId)
                .orElseThrow(WechatFamilyAccessDeniedException::new);
        access.requireAccess(confirmation.getConfirmedBy(), familyId,
                WechatFamilyAccessGuard.AccessOperation.CONFIRM_TASK);
        var source = messages.findByIdAndFamilyId(confirmation.getConfirmationMessageId(), familyId)
                .orElseThrow(() -> new IllegalStateException("确认结果缺少来源消息"));
        var tasks = links.findByConfirmationIdAndFamilyIdOrderByCandidateItemIndexAsc(confirmationId, familyId);
        if (tasks.isEmpty()) throw new IllegalStateException("确认结果缺少任务关联");
        // 经验失败不妨碍创建结果投递；独立扫描器将重试，不重新创建任务。
        try { experiences.projectConfirmation(familyId, confirmationId, now); }
        catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(WechatConfirmationResultProjection.class)
                    .warn("确认经验投影延期 family_id={} confirmation_id={} reason=EXPERIENCE_PROJECTION_FAILED",
                            familyId, confirmationId);
        }
        outbox.enqueueReply(source, familyId, confirmation.getConversationId(),
                confirmation.getIdempotencyKey() + ":result", "已创建 " + tasks.size() + " 项任务。", now);
    }
}
