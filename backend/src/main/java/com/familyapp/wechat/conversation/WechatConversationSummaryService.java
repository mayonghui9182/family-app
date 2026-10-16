package com.familyapp.wechat.conversation;

import com.familyapp.wechat.conversation.WechatConversationAgentClient.RecentMessage;
import com.familyapp.wechat.conversation.WechatConversationSummaryClient.*;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 只读快照与游标 CAS 分离；摘要绝不回填 slot、候选、确认或任务。 */
@Service
public class WechatConversationSummaryService {
    @org.springframework.beans.factory.annotation.Autowired
    private WechatConversationOperationalMetrics metrics;
    private final WechatTaskConversationRepository conversations;
    private final WechatConversationMessageRepository messages;
    private final WechatFamilyAccessGuard access;
    private final WechatConversationProperties properties;
    private final WechatConversationSummaryClient codec;
    public WechatConversationSummaryService(WechatTaskConversationRepository conversations,
            WechatConversationMessageRepository messages, WechatFamilyAccessGuard access,
            WechatConversationProperties properties, WechatConversationSummaryClient codec) {
        this.conversations=conversations; this.messages=messages; this.access=access;
        this.properties=properties; this.codec=codec;
    }

    @Transactional(readOnly=true)
    public Optional<Snapshot> prepare(Long familyId, Long id, Instant now) {
        var conversation = conversations.findByIdAndFamilyId(id,familyId).orElse(null);
        if (conversation == null || conversation.getDeletedAt()!=null
                || access.findAccess(conversation.getUserId(),familyId).isEmpty()) return Optional.empty();
        Long cursor=conversation.getSummaryThroughMessageId();
        Summary previous=codec.decode(conversation.getRollingSummary()).orElse(null);
        boolean broken = cursor != null && (previous == null || !cursor.equals(previous.lastMessageId())
                || messages.findByIdAndFamilyId(cursor,familyId)
                .filter(item -> Objects.equals(item.getConversationId(),id)).isEmpty());
        var range=messages.summaryRange(familyId,id,broken || cursor==null ? 0 : cursor,
                now.minus(properties.getRawMessageRetentionDays(),ChronoUnit.DAYS),PageRequest.of(0,100));
        if (range.isEmpty() || range.stream().anyMatch(item -> item.getProcessingStatus()==MessageProcessingStatus.PROCESSING
                || item.getProcessingStatus()==MessageProcessingStatus.PENDING)) return Optional.empty();
        var usable=range.stream().filter(item -> item.getContent()!=null && !item.getContent().isBlank()).toList();
        if (usable.isEmpty()) return Optional.empty();
        long turns=usable.stream().filter(item -> item.getDirection()==MessageDirection.INBOUND).count();
        long chars=usable.stream().mapToLong(item -> item.getContent().length()).sum();
        boolean ended=conversation.getStatus()==ConversationStatus.PAUSED || !conversation.getStatus().isRecoverable();
        if (!broken && !ended && turns<properties.getSummaryEveryTurns()
                && chars + (conversation.getRollingSummary()==null ? 0 : conversation.getRollingSummary().length())
                < properties.getSummaryContextChars()) return Optional.empty();
        if (broken && metrics != null) metrics.event(WechatConversationOperationalMetrics.Event.SUMMARY_REBUILD);
        var recent=usable.stream().map(item -> new RecentMessage(item.getId(),item.getDirection(),
                item.getContent().substring(0,Math.min(2000,item.getContent().length())),item.getCreatedAt())).toList();
        return Optional.of(new Snapshot(familyId,id,conversation.getUserId(),conversation.getVersion(),cursor,
                messages.latestId(familyId,id),new Request(id,broken || cursor==null ? null : previous,recent),broken));
    }

    @Transactional
    public boolean commit(Snapshot snapshot, Summary result, Instant now) {
        var conversation=conversations.findByIdAndFamilyIdForUpdate(snapshot.conversationId(),snapshot.familyId()).orElse(null);
        if (conversation==null || !Objects.equals(conversation.getUserId(),snapshot.userId())
                || access.findAccess(snapshot.userId(),snapshot.familyId()).isEmpty()
                || messages.latestId(snapshot.familyId(),snapshot.conversationId())!=snapshot.latestMessageId()
                || !Objects.equals(conversation.getVersion(),snapshot.version())
                || !Objects.equals(conversation.getSummaryThroughMessageId(),snapshot.previousCursor())
                || !result.lastMessageId().equals(snapshot.request().messages().get(snapshot.request().messages().size()-1).messageId())) return false;
        return conversations.commitSummary(snapshot.conversationId(),snapshot.familyId(),snapshot.version(),
                snapshot.previousCursor(),codec.encode(result),result.lastMessageId(),snapshot.latestMessageId(),now)==1;
    }

    public record Snapshot(Long familyId,Long conversationId,Long userId,Long version,Long previousCursor,
            long latestMessageId,Request request,boolean rebuilt) { }
}
