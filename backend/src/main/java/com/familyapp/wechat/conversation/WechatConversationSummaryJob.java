package com.familyapp.wechat.conversation;

import com.familyapp.family.FamilyRepository;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.PageRequest;
import java.time.Clock;

/** 摘要轮询不持有事务；单会话失败隔离，不影响任务确认和后续会话。 */
@Component
public class WechatConversationSummaryJob {
    private final FamilyRepository families;
    private final WechatTaskConversationRepository conversations;
    private final WechatConversationSummaryService summaries;
    private final WechatConversationSummaryClient client;
    private final WechatConversationProperties properties;
    private final Clock clock;
    private int page;
    public WechatConversationSummaryJob(FamilyRepository families, WechatTaskConversationRepository conversations,
            WechatConversationSummaryService summaries, WechatConversationSummaryClient client,
            WechatConversationProperties properties,Clock clock) {
        this.families=families; this.conversations=conversations; this.summaries=summaries;
        this.client=client; this.properties=properties; this.clock=clock;
    }
    @Scheduled(fixedDelayString="${wechat.conversation.summary-poll-delay-ms:5000}", initialDelay=15000)
    public void poll() {
        if (!properties.isEnabled() || !properties.isWorkerEnabled()) return;
        boolean found=false;
        for (Long familyId:families.findAllActiveIds()) {
            var batch=conversations.summaryCandidates(familyId,PageRequest.of(page,20));
            if (!batch.isEmpty()) found=true;
            for (var item:batch) summarize(familyId,item.getId());
        }
        // 分页轮巡，避免不足六轮的早期会话阻塞其他家庭的摘要。
        page=found ? page+1 : 0;
    }
    public boolean summarize(Long familyId,Long id) {
        try {
            var snapshot=summaries.prepare(familyId,id,clock.instant());
            if (snapshot.isEmpty()) return false;
            var result=client.summarize(snapshot.get().request());
            return result.isPresent() && summaries.commit(snapshot.get(),result.get(),clock.instant());
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
