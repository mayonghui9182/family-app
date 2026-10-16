package com.familyapp.wechat.conversation;

import com.familyapp.family.FamilyRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;

/** 提交后崩溃恢复：定期补齐已提交确认但尚未生成的结果 outbox。 */
@Component
public class WechatConfirmationResultRecoveryJob {
    private static final Logger log = LoggerFactory.getLogger(WechatConfirmationResultRecoveryJob.class);
    private final FamilyRepository families;
    private final WechatTaskConfirmationRepository confirmations;
    private final WechatConfirmationResultProjection projection;
    private final WechatConversationProperties properties;
    private final Clock clock;

    public WechatConfirmationResultRecoveryJob(FamilyRepository families,
            WechatTaskConfirmationRepository confirmations, WechatConfirmationResultProjection projection,
            WechatConversationProperties properties, Clock clock) {
        this.families = families;
        this.confirmations = confirmations;
        this.projection = projection;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${wechat.conversation.poll-fixed-delay-ms:1000}", initialDelay = 15000)
    public void recover() {
        if (!properties.isEnabled() || !properties.isWorkerEnabled()) return;
        for (Long familyId : families.findAllActiveIds()) recoverFamily(familyId);
    }

    public void recoverFamily(Long familyId) {
        for (var confirmation : confirmations.missingResults(familyId, PageRequest.of(0, 20))) {
            try {
                projection.project(familyId, confirmation.getId(), clock.instant());
            } catch (RuntimeException exception) {
                log.warn("确认结果恢复失败 family_id={} confirmation_id={} error_type={}",
                        familyId, confirmation.getId(), exception.getClass().getSimpleName());
            }
        }
    }
}
