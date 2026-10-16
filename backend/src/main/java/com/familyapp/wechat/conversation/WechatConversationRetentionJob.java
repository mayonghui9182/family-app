package com.familyapp.wechat.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;

/** 只记录清理数量和稳定原因；独立开关默认关闭，不输出正文、公众号或微信标识。 */
@Component
public class WechatConversationRetentionJob {
    private static final Logger LOG = LoggerFactory.getLogger(WechatConversationRetentionJob.class);
    private final WechatConversationRetentionService retention;
    private final WechatConversationProperties properties;
    private final Clock clock;
    private int page;

    public WechatConversationRetentionJob(WechatConversationRetentionService retention,
            WechatConversationProperties properties, Clock clock) {
        this.retention = retention; this.properties = properties; this.clock = clock;
    }

    @Scheduled(fixedDelayString="${wechat.conversation.retention-poll-delay-ms:60000}", initialDelay=30000)
    public void poll() {
        if (!properties.isEnabled() || !properties.isWorkerEnabled() || !properties.isRetentionEnabled()) return;
        var families = retention.familyScopes(page);
        var accounts = retention.unroutedScopes(page);
        int messages = 0, summaries = 0, candidates = 0;
        for (Long familyId : families) {
            try {
                var result = retention.cleanFamily(familyId, clock.instant());
                messages += result.messages(); summaries += result.summaries(); candidates += result.candidates();
            } catch (RuntimeException exception) { LOG.warn("微信保留清理失败 familyId={} reason=RETENTION_FAILED", familyId); }
        }
        for (String account : accounts) {
            try { messages += retention.cleanUnrouted(account, clock.instant()); }
            catch (RuntimeException exception) { LOG.warn("微信未路由保留清理失败 reason=RETENTION_FAILED"); }
        }
        if (messages + summaries + candidates > 0)
            LOG.info("微信保留清理完成 messages={} summaries={} candidates={}", messages, summaries, candidates);
        page = families.isEmpty() && accounts.isEmpty() ? 0 : page + 1;
    }
}
