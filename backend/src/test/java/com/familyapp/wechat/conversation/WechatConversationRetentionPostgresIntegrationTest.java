package com.familyapp.wechat.conversation;

import com.familyapp.wechat.conversation.WechatConversationLeaseService.MessageLease;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 只在本次脚本创建的随机独立 PostgreSQL 库运行，不能指向业务库。 */
@EnabledIfEnvironmentVariable(named="FAMILYAPP_POSTGRES_TEST_URL",
        matches="jdbc:postgresql://[^/]+/familyapp_wechat_it_default_[a-f0-9]{32}")
@SpringBootTest(properties={
        "spring.datasource.url=${FAMILYAPP_POSTGRES_TEST_URL}",
        "spring.datasource.username=${FAMILYAPP_POSTGRES_TEST_USER}",
        "spring.datasource.password=${FAMILYAPP_POSTGRES_TEST_PASSWORD}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "wechat.notify.enabled=false"})
@ActiveProfiles("test")
class WechatConversationRetentionPostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired WechatTaskConversationRepository conversations;
    @Autowired WechatConversationMessageRepository messages;
    @Autowired WechatCandidateVersionRepository candidates;
    @Autowired WechatChatContextRepository contexts;
    @Autowired WechatTaskConfirmationService confirmation;
    @Autowired WechatConversationRetentionService retention;
    @Autowired com.familyapp.family.FamilyService families;
    @Autowired FamilyTaskExperienceProjection experienceProjection;
    @Autowired FamilyTaskExperienceSelectionService experienceSelection;

    @Test
    void realPostgresEmptyFamilyExitWithConfirmedParentAndChildPreservesBindingAndOtherFamily() {
        Instant now=Instant.parse("2026-10-04T00:00:00Z");
        var request=new com.familyapp.family.dto.CreateFamilyRequest(); request.setName("物理删除验收家庭");
        Long familyId=families.createFamily(request,1L).id();
        jdbc.update("INSERT INTO wechat_bind(id,user_id,openid) VALUES(89002,1,'deletion-pg-openid')");
        var seed=tx.execute(status -> {
            var context=new WechatChatContext(); context.setWechatBindId(89002L); context.setUserId(1L);
            context.setCurrentFamilyId(familyId); context.setDefaultFamilyId(1L);
            context.setLeaseOwner("deletion-pg"); context.setLeaseUntil(now.plusSeconds(300)); contexts.save(context);
            var conversation=new WechatTaskConversation(); conversation.setFamilyId(familyId); conversation.setUserId(1L);
            conversation.setWechatBindId(89002L); conversation.setStatus(ConversationStatus.AWAITING_CONFIRMATION);
            conversation.setCurrentRevision(1); conversation.setExpiresAt(now.plusSeconds(3600)); conversations.save(conversation);
            var source=message(now,"pg-deletion-confirmation",conversation.getId());
            source.setFamilyId(familyId); source.setWechatBindId(89002L); source.setOpenid("deletion-pg-openid");
            source.setLeaseOwner("deletion-pg"); messages.save(source);
            var candidate=new WechatCandidateVersion(); candidate.setFamilyId(familyId); candidate.setConversationId(conversation.getId());
            candidate.setRevision(1); candidate.setCandidatePayload(java.util.Collections.nCopies(3,
                    Map.of("title","待物理删除的任务","priority","MEDIUM")));
            candidate.setRenderedPreview("版本 1"); candidate.setSourceMessageId(source.getId());
            candidate.setModelName("test"); candidate.setPromptVersion("test"); candidates.save(candidate);
            return new long[]{conversation.getId(),source.getId()};
        });
        assertNotNull(seed);
        var confirmed=confirmation.confirm(new MessageLease(seed[1],89002L,"deletion-pg",now.plusSeconds(300),1),1L,familyId,seed[0],now);
        assertTrue(confirmed.accepted());
        var first = java.util.concurrent.CompletableFuture.runAsync(() ->
                experienceProjection.projectConfirmation(familyId, confirmed.confirmationId(), now));
        var second = java.util.concurrent.CompletableFuture.runAsync(() ->
                experienceProjection.projectConfirmation(familyId, confirmed.confirmationId(), now));
        assertDoesNotThrow(() -> java.util.concurrent.CompletableFuture.allOf(first, second)
                .get(15, java.util.concurrent.TimeUnit.SECONDS));
        var active = experienceSelection.select(familyId, "待物理删除的任务");
        assertEquals(1, active.size());
        assertEquals(3, active.get(0).getVersion());
        assertEquals(3, active.get(0).getEvidenceCount());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM family_task_experience_evidence WHERE family_id=? AND experience_id=?",
                Integer.class, familyId, active.get(0).getId()));
        tx.executeWithoutResult(status -> {
            var child=new WechatTaskConversation(); child.setFamilyId(familyId); child.setUserId(1L);
            child.setWechatBindId(89002L); child.setParentConversationId(seed[0]);
            child.setStatus(ConversationStatus.CANCELLED); child.setActiveSlot(null);
            child.setExpiresAt(now.plusSeconds(3600)); conversations.save(child);
        });
        int otherMessages=jdbc.queryForObject("SELECT COUNT(*) FROM wechat_conversation_messages WHERE family_id=1",Integer.class);
        try {
            com.familyapp.auth.UserContext.setCurrent(new com.familyapp.auth.UserContext.CurrentUser(1L,null,null,familyId,"OWNER"));
            families.leaveFamily(familyId);
        } finally { com.familyapp.auth.UserContext.clear(); }
        for(String table:List.of("wechat_task_conversations","wechat_conversation_messages","wechat_candidate_versions",
                "wechat_task_confirmations","wechat_confirmation_tasks","family_task_experience_evidence",
                "family_task_experiences","tasks","family_members"))
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE family_id=?",Integer.class,familyId));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM families WHERE id=?",Integer.class,familyId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM wechat_bind WHERE id=89002",Integer.class));
        var route=contexts.findByWechatBindId(89002L).orElseThrow();
        assertNull(route.getCurrentFamilyId()); assertEquals(1L,route.getDefaultFamilyId());
        assertEquals(otherMessages,jdbc.queryForObject("SELECT COUNT(*) FROM wechat_conversation_messages WHERE family_id=1",Integer.class));
    }

    @Test
    void realForeignKeysPermitContentRetirementWhilePreservingConfirmationReplay() {
        Instant now = Instant.parse("2026-10-04T00:00:00Z");
        jdbc.update("INSERT INTO wechat_bind(id,user_id,openid) VALUES(89001,1,'retention-pg-openid')");
        var seed = tx.execute(status -> {
            var context = new WechatChatContext();
            context.setWechatBindId(89001L); context.setUserId(1L); context.setCurrentFamilyId(1L);
            context.setLeaseOwner("retention-pg"); context.setLeaseUntil(now.plusSeconds(300));
            contexts.save(context);
            var conversation = new WechatTaskConversation();
            conversation.setFamilyId(1L); conversation.setUserId(1L); conversation.setWechatBindId(89001L);
            conversation.setStatus(ConversationStatus.AWAITING_CONFIRMATION); conversation.setCurrentRevision(1);
            conversation.setExpiresAt(now.plusSeconds(3600)); conversations.save(conversation);
            var message = message(now, "pg-original-confirmation", conversation.getId());
            messages.save(message);
            var candidate = new WechatCandidateVersion();
            candidate.setFamilyId(1L); candidate.setConversationId(conversation.getId()); candidate.setRevision(1);
            candidate.setCandidatePayload(List.of(Map.of("title", "保留的实际任务", "priority", "MEDIUM")));
            candidate.setRenderedPreview("版本 1"); candidate.setSourceMessageId(message.getId());
            candidate.setModelName("test"); candidate.setPromptVersion("test"); candidates.save(candidate);
            return new long[]{conversation.getId(), message.getId()};
        });
        assertNotNull(seed);
        var committed = confirmation.confirm(lease(seed[1], now), 1L, 1L, seed[0], now);
        assertTrue(committed.accepted());
        jdbc.update("UPDATE wechat_conversation_messages SET processing_status='PROCESSED', received_at=? WHERE family_id=1 AND id=?",
                Timestamp.from(now.minusSeconds(31L * 86400)), seed[1]);
        jdbc.update("UPDATE wechat_candidate_versions SET created_at=? WHERE family_id=1 AND conversation_id=?",
                Timestamp.from(now.minusSeconds(181L * 86400)), seed[0]);
        tx.executeWithoutResult(status -> {
            var conversation = conversations.findByIdAndFamilyId(seed[0], 1L).orElseThrow();
            conversation.setLastActivityAt(now.minusSeconds(181L * 86400));
            conversation.setRollingSummary("待清理摘要");
            conversation.setSlotState(Map.of("topic", Map.of("value", "待清理事实")));
            conversations.save(conversation);
        });
        assertEquals(new WechatConversationRetentionService.Counts(0,0,0), retention.cleanFamily(2L, now));
        assertEquals(new WechatConversationRetentionService.Counts(1,1,1), retention.cleanFamily(1L, now));
        assertTrue(conversations.findByIdAndFamilyId(seed[0],1L).orElseThrow().getSlotState().isEmpty());
        assertEquals(0, retention.cleanFamily(1L,now).summaries());
        Long retry = tx.execute(status -> messages.save(message(now, "pg-retry-confirmation", seed[0])).getId());
        assertEquals(committed.taskIds(), confirmation.confirm(lease(retry, now),1L,1L,seed[0],now).taskIds());
        assertEquals(64, jdbc.queryForObject("SELECT LENGTH(candidate_hash) FROM wechat_task_confirmations WHERE family_id=1 AND id=?",
                Integer.class, committed.confirmationId()));
        assertNull(jdbc.queryForObject("SELECT candidate_version_id FROM wechat_task_confirmations WHERE family_id=1 AND id=?",
                Long.class, committed.confirmationId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM wechat_confirmation_tasks WHERE family_id=1 AND confirmation_id=?",
                Integer.class, committed.confirmationId()));
    }

    private WechatConversationMessage message(Instant now, String externalId, Long conversationId) {
        var message = new WechatConversationMessage();
        message.setWechatAccountId("retention-pg"); message.setExternalMsgId(externalId);
        message.setOpenid("retention-pg-openid"); message.setContent("确认创建 1");
        message.setWechatBindId(89001L); message.setUserId(1L); message.setFamilyId(1L);
        message.setConversationId(conversationId); message.setDirection(MessageDirection.INBOUND);
        message.setReceivedAt(now); message.setAvailableAt(now);
        message.setProcessingStatus(MessageProcessingStatus.PROCESSING);
        message.setLeaseOwner("retention-pg"); message.setLeaseUntil(now.plusSeconds(300));
        return message;
    }

    private MessageLease lease(Long id, Instant now) {
        return new MessageLease(id,89001L,"retention-pg",now.plusSeconds(300),1);
    }
}
