package com.familyapp.wechat.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;

/** 已删除家庭修复仅记录内部 ID 和稳定原因，不输出正文或异常响应。 */
@Component
public class WechatFamilyDeletionRecoveryJob {
    private static final Logger LOG=LoggerFactory.getLogger(WechatFamilyDeletionRecoveryJob.class);
    private final WechatFamilyDeletionService deletion;
    private final WechatConversationProperties properties;
    private final Clock clock;
    private int page;
    public WechatFamilyDeletionRecoveryJob(WechatFamilyDeletionService deletion, WechatConversationProperties properties, Clock clock) {
        this.deletion=deletion; this.properties=properties; this.clock=clock;
    }
    @Scheduled(fixedDelayString="${wechat.conversation.deletion-recovery-delay-ms:60000}", initialDelay=30000)
    public void poll() {
        if (!properties.isEnabled() || !properties.isWorkerEnabled()) return;
        var ids=deletion.deletedFamilies(page);
        for (Long id:ids) {
            try { deletion.repairDeletedFamily(id,clock.instant()); }
            catch (RuntimeException exception) { LOG.warn("家庭会话删除修复失败 familyId={} reason=DELETION_RECOVERY_FAILED",id); }
        }
        page=ids.isEmpty() ? 0 : page+1;
    }
}
