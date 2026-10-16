package com.familyapp.wechat.conversation;

import com.familyapp.wechat.conversation.WechatConversationSummaryClient.Summary;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.data.domain.PageRequest;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:wechatsummary;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@Sql("classpath:db/v6-test-setup.sql")
class WechatConversationSummaryServiceTest {
    private static final Instant NOW=Instant.parse("2026-10-04T04:00:00Z");
    @Autowired WechatConversationSummaryService service;
    @Autowired WechatConversationSummaryJob job;
    @Autowired WechatConversationRetentionService retention;
    @Autowired WechatTaskConversationRepository conversations;
    @Autowired WechatConversationMessageRepository messages;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @SpyBean WechatConversationSummaryClient client;
    private Long id;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM wechat_conversation_messages WHERE family_id=1");
        jdbc.update("DELETE FROM wechat_task_conversations WHERE family_id=1");
    }

    @Test
    void sixthRoundSummarizesOutsideTransactionAndDoesNotChangeBusinessState() {
        seed(5,ConversationStatus.ACTIVE);
        assertTrue(service.prepare(1L,id,NOW).isEmpty());
        append(MessageDirection.INBOUND,"第六轮用户明确内容");
        append(MessageDirection.OUTBOUND,"请确认当前候选");
        var snapshot=service.prepare(1L,id,NOW).orElseThrow();
        assertEquals(12,snapshot.request().messages().size());
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var request=invocation.getArgument(0,WechatConversationSummaryClient.Request.class);
            return Optional.of(result(request.messages().get(request.messages().size()-1).messageId()));
        }).when(client).summarize(any());
        assertTrue(job.summarize(1L,id));
        var saved=conversations.findByIdAndFamilyId(id,1L).orElseThrow();
        assertEquals(snapshot.latestMessageId(),saved.getSummaryThroughMessageId());
        assertEquals(2,saved.getCurrentRevision());
        assertEquals(ConversationStatus.ACTIVE,saved.getStatus());
        assertEquals("买菜",((Map<?,?>)saved.getSlotState().get("topic")).get("value"));
        assertTrue(service.prepare(1L,id,NOW).isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE family_id=1",Integer.class));
    }

    @Test
    void newMessageDuringHttpRejectsOldCursorAndCompetingSummaryCannotOverwrite() {
        seed(6,ConversationStatus.ACTIVE);
        var snapshot=service.prepare(1L,id,NOW).orElseThrow();
        append(MessageDirection.INBOUND,"刚发的新消息");
        assertFalse(service.commit(snapshot,result(snapshot.latestMessageId()),NOW));
        assertNull(conversations.findByIdAndFamilyId(id,1L).orElseThrow().getRollingSummary());
        var next=service.prepare(1L,id,NOW).orElseThrow();
        assertTrue(service.commit(next,result(next.latestMessageId()),NOW));
        assertFalse(service.commit(next,result(next.latestMessageId()),NOW));
    }

    @Test
    void pausedAndTerminalTriggerImmediatelyAndWrongFamilyIsInvisible() {
        for (var status:List.of(ConversationStatus.PAUSED,ConversationStatus.CONFIRMED,
                ConversationStatus.CANCELLED,ConversationStatus.EXPIRED)) {
            seed(1,status);
            assertTrue(service.prepare(1L,id,NOW).isPresent());
            assertTrue(service.prepare(2L,id,NOW).isEmpty());
            cleanup();
        }
    }

    @Test
    void contextThresholdTriggersBeforeSixTurnsAndProcessingMessageDefersSummary() {
        seed(2,ConversationStatus.ACTIVE);
        for (int index=0; index<3; index++) append(MessageDirection.OUTBOUND,"长上下文".repeat(600));
        assertTrue(service.prepare(1L,id,NOW).isPresent());
        var pending=append(MessageDirection.INBOUND,"还在处理");
        jdbc.update("UPDATE wechat_conversation_messages SET processing_status='PROCESSING' WHERE id=? AND family_id=1",pending);
        assertTrue(service.prepare(1L,id,NOW).isEmpty());
    }

    @Test
    void brokenCursorRebuildsOnlyRetainedMessagesWithoutTrustingPreviousSummary() {
        seed(1,ConversationStatus.ACTIVE);
        tx.executeWithoutResult(status -> {
            var conversation=conversations.findByIdAndFamilyId(id,1L).orElseThrow();
            conversation.setRollingSummary(client.encode(result(999999L)));
            conversation.setSummaryThroughMessageId(999999L);
            conversations.save(conversation);
        });
        assertFalse(conversations.summaryCandidates(1L,PageRequest.of(0,20)).isEmpty());
        var snapshot=service.prepare(1L,id,NOW).orElseThrow();
        assertTrue(snapshot.rebuilt());
        assertNull(snapshot.request().previousSummary());
        assertTrue(service.commit(snapshot,result(snapshot.latestMessageId()),NOW));
        var saved=conversations.findByIdAndFamilyId(id,1L).orElseThrow();
        assertNotEquals(999999L,saved.getSummaryThroughMessageId());
    }

    @Test
    void removedMemberHiddenConversationOrChangedStateCannotCommitSummary() {
        seed(6,ConversationStatus.ACTIVE);
        var snapshot=service.prepare(1L,id,NOW).orElseThrow();
        tx.executeWithoutResult(status -> {
            var conversation=conversations.findByIdAndFamilyId(id,1L).orElseThrow();
            conversation.setStatus(ConversationStatus.PAUSED);
            conversation.setPausedFromStatus(ConversationStatus.ACTIVE);
            conversations.save(conversation);
        });
        assertFalse(service.commit(snapshot,result(snapshot.latestMessageId()),NOW));
        var changed=service.prepare(1L,id,NOW).orElseThrow();
        jdbc.update("UPDATE family_members SET left_at=CURRENT_TIMESTAMP WHERE family_id=1 AND user_id=1");
        assertFalse(service.commit(changed,result(changed.latestMessageId()),NOW));
        assertTrue(service.prepare(1L,id,NOW).isEmpty());
    }

    private Summary result(Long last) {
        return new Summary(List.of("用户明确要买菜"),List.of("时间待确认"),List.of(),List.of("候选不是创建授权"),last);
    }

    @Test
    void retentionDuringSummaryHttpRejectsOldContentEvenWhenMessageIdsRemain() {
        seed(6, ConversationStatus.ACTIVE);
        var snapshot = service.prepare(1L, id, NOW).orElseThrow();
        assertEquals(12, retention.cleanFamily(1L, NOW.plusSeconds(31L * 86400)).messages());
        assertEquals(snapshot.latestMessageId(), messages.latestId(1L, id));
        assertFalse(service.commit(snapshot, result(snapshot.latestMessageId()), NOW.plusSeconds(31L * 86400)));
        assertNull(conversations.findByIdAndFamilyId(id, 1L).orElseThrow().getRollingSummary());
    }

    private void seed(int rounds,ConversationStatus status) {
        id=tx.execute(transaction -> {
            var value=new WechatTaskConversation();
            value.setFamilyId(1L); value.setUserId(1L); value.setWechatBindId(991L);
            value.setStatus(status); value.setCurrentRevision(2);
            if (!status.isRecoverable()) value.setActiveSlot(null);
            if (status==ConversationStatus.PAUSED) value.setPausedFromStatus(ConversationStatus.ACTIVE);
            value.setExpiresAt(NOW.plusSeconds(3600));
            value.setSlotState(Map.of("topic",Map.of("value","买菜","source","USER_EXPLICIT","source_message_id",1)));
            return conversations.save(value).getId();
        });
        for(int round=0;round<rounds;round++) {
            append(MessageDirection.INBOUND,"用户第"+round+"轮");
            append(MessageDirection.OUTBOUND,"助手第"+round+"轮");
        }
    }

    private Long append(MessageDirection direction,String content) {
        return tx.execute(status -> {
            var message=new WechatConversationMessage();
            message.setWechatAccountId("summary-account"); message.setExternalMsgId(UUID.randomUUID().toString());
            message.setOpenid("summary-openid"); message.setWechatBindId(991L); message.setUserId(1L);
            message.setFamilyId(1L); message.setConversationId(id); message.setDirection(direction); message.setContent(content);
            message.setReceivedAt(NOW); message.setAvailableAt(NOW); message.setProcessedAt(NOW);
            message.setProcessingStatus(MessageProcessingStatus.PROCESSED);
            return messages.save(message).getId();
        });
    }
}
