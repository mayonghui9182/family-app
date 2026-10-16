package com.familyapp.wechat.conversation;

import com.familyapp.family.FamilyRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;

/** 小批量扫描已确认关联，修复提交后崩溃并观察后续真实任务结果。 */
@Component
public class FamilyTaskExperienceRecoveryJob {
    private static final Logger log = LoggerFactory.getLogger(FamilyTaskExperienceRecoveryJob.class);
    private final FamilyRepository families;
    private final WechatConfirmationTaskRepository links;
    private final FamilyTaskExperienceProjection projection;
    private final WechatConversationProperties properties;
    private final Clock clock;

    public FamilyTaskExperienceRecoveryJob(FamilyRepository families, WechatConfirmationTaskRepository links,
            FamilyTaskExperienceProjection projection, WechatConversationProperties properties, Clock clock) {
        this.families = families;
        this.links = links;
        this.projection = projection;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${wechat.conversation.experience-fixed-delay-ms:60000}", initialDelay = 30000)
    public void recover() {
        if (!properties.isEnabled() || !properties.isWorkerEnabled()) return;
        for (var family : families.findAllActiveIds()) {
            for (int page = 0; ; page++) {
                var batch = links.findByFamilyIdOrderByIdAsc(family, PageRequest.of(page, 50));
                for (var link : batch) {
                    try { projection.observe(family, link.getId(), clock.instant()); }
                    catch (RuntimeException exception) {
                        log.warn("经验投影失败 family_id={} link_id={} reason=EXPERIENCE_PROJECTION_FAILED", family, link.getId());
                    }
                }
                if (batch.size() < 50) break;
            }
        }
    }
}
