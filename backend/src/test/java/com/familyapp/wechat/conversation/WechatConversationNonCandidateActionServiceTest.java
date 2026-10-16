package com.familyapp.wechat.conversation;

import com.familyapp.family.FamilyRole;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.ActionType;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.FamilyContext;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.SlotSource;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.SlotValue;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnAction;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnCallResult;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnRequest;
import com.familyapp.wechat.conversation.WechatConversationLeaseService.MessageLease;
import com.familyapp.wechat.conversation.WechatConversationTurnPreparationService.PreparedTurn;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class WechatConversationNonCandidateActionServiceTest {

    private static final AtomicLong NEXT_ID = new AtomicLong(9_500_000L);
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final String WORKER_ID = "worker-action-test";

    @Autowired
    private WechatConversationNonCandidateActionService service;

    @Autowired
    private WechatChatContextRepository contextRepository;

    @Autowired
    private WechatTaskConversationRepository conversationRepository;

    @Autowired
    private WechatConversationMessageRepository messageRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdBindIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long bindId : createdBindIds) {
            jdbcTemplate.update(
                    "delete from wechat_conversation_messages where wechat_bind_id = ?",
                    bindId);
            jdbcTemplate.update(
                    "delete from wechat_task_conversations where wechat_bind_id = ?",
                    bindId);
            jdbcTemplate.update(
                    "delete from wechat_chat_context where wechat_bind_id = ?",
                    bindId);
        }
    }

    @Test
    void askPersistsSourceMarkersAndRetryDoesNotAppendReply() {
        Seed seed = seed(ConversationStatus.AWAITING_CONFIRMATION);
        Map<String, SlotValue> patch = new LinkedHashMap<>();
        patch.put("title", new SlotValue(
                "周五买菜",
                SlotSource.USER_EXPLICIT,
                seed.messageId(),
                null));
        patch.put("priority", new SlotValue(
                "HIGH",
                SlotSource.MODEL_INFERRED,
                seed.messageId(),
                null));
        TurnCallResult result = result(
                ActionType.ASK_CLARIFICATION,
                "还需要指定由谁负责吗？",
                patch);
        PreparedTurn turn = turn(seed);

        var first = service.apply(seed.lease(), turn, result, NOW);
        var retry = service.apply(seed.lease(), turn, result, NOW.plusSeconds(1));

        assertThat(first.applied()).isTrue();
        assertThat(first.duplicate()).isFalse();
        assertThat(retry.applied()).isFalse();
        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.replyMessageId()).isEqualTo(first.replyMessageId());
        assertThat(outboundCount(seed.bindId())).isEqualTo(1);

        Snapshot snapshot = snapshot(seed);
        assertThat(snapshot.sourceStatus()).isEqualTo(MessageProcessingStatus.PROCESSED);
        assertThat(snapshot.sourceLeaseOwner()).isNull();
        assertThat(snapshot.contextLeaseOwner()).isNull();
        assertThat(snapshot.status()).isEqualTo(ConversationStatus.ACTIVE);
        assertThat(snapshot.currentRevision()).isEqualTo(7);
        assertThat(snapshot.slotState()).containsKey("notes");

        Map<?, ?> explicit = slot(snapshot.slotState(), "title");
        assertThat(explicit.get("value")).isEqualTo("周五买菜");
        assertThat(explicit.get("source")).isEqualTo("USER_EXPLICIT");
        assertThat(((Number) explicit.get("source_message_id")).longValue())
                .isEqualTo(seed.messageId());
        assertThat(explicit.containsKey("confirmed")).isFalse();

        Map<?, ?> inferred = slot(snapshot.slotState(), "priority");
        assertThat(inferred.get("value")).isEqualTo("HIGH");
        assertThat(inferred.get("source")).isEqualTo("MODEL_INFERRED");
        assertThat(((Number) inferred.get("source_message_id")).longValue())
                .isEqualTo(seed.messageId());
        assertThat(inferred.containsKey("confirmed")).isFalse();
    }

    @Test
    void refuseCompletesMessageWithoutAdvancingRevision() {
        Seed seed = seed(ConversationStatus.ACTIVE);
        TurnCallResult result = result(
                ActionType.REFUSE_OR_HELP,
                "我只能协助创建家庭任务。你可以说“新建任务”。",
                Map.of());

        var applied = service.apply(seed.lease(), turn(seed), result, NOW);

        assertThat(applied.applied()).isTrue();
        Snapshot snapshot = snapshot(seed);
        assertThat(snapshot.status()).isEqualTo(ConversationStatus.ACTIVE);
        assertThat(snapshot.currentRevision()).isEqualTo(7);
        assertThat(snapshot.slotState()).containsOnlyKeys("notes");
        assertThat(outboundCount(seed.bindId())).isEqualTo(1);
    }

    @Test
    void invalidModelSourceRollsBackAllWrites() {
        Seed seed = seed(ConversationStatus.ACTIVE);
        TurnCallResult invalid = result(
                ActionType.ASK_CLARIFICATION,
                "请再补充一下。",
                Map.of("title", new SlotValue(
                        "买菜",
                        SlotSource.MODEL_INFERRED,
                        seed.messageId() + 1,
                        null)));

        assertThatThrownBy(() ->
                service.apply(seed.lease(), turn(seed), invalid, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("消息槽位来源无效");

        Snapshot snapshot = snapshot(seed);
        assertThat(snapshot.sourceStatus()).isEqualTo(MessageProcessingStatus.PROCESSING);
        assertThat(snapshot.sourceLeaseOwner()).isEqualTo(WORKER_ID);
        assertThat(snapshot.contextLeaseOwner()).isEqualTo(WORKER_ID);
        assertThat(snapshot.slotState()).containsOnlyKeys("notes");
        assertThat(outboundCount(seed.bindId())).isZero();
    }


    @Test
    void cancelledConversationDiscardsDelayedAction() {
        Seed seed = seed(ConversationStatus.ACTIVE);
        PreparedTurn prepared = turn(seed);
        transactionTemplate.executeWithoutResult(transaction -> {
            WechatTaskConversation conversation = conversationRepository
                    .findByIdAndFamilyId(seed.conversationId(), seed.familyId())
                    .orElseThrow();
            conversation.setStatus(ConversationStatus.CANCELLED);
            conversationRepository.save(conversation);
        });

        TurnCallResult result = result(
                ActionType.ASK_CLARIFICATION,
                "这条回复不应写入。",
                Map.of());
        var applied = service.apply(seed.lease(), prepared, result, NOW);

        assertThat(applied.stale()).isTrue();
        assertThat(applied.staleReason())
                .isEqualTo(WechatConversationAiResponseFence.StaleReason.STATUS_CHANGED);
        Snapshot snapshot = snapshot(seed);
        assertThat(snapshot.sourceStatus()).isEqualTo(MessageProcessingStatus.SKIPPED);
        assertThat(snapshot.contextLeaseOwner()).isNull();
        assertThat(snapshot.status()).isEqualTo(ConversationStatus.CANCELLED);
        assertThat(snapshot.slotState()).containsOnlyKeys("notes");
        assertThat(outboundCount(seed.bindId())).isZero();

        WechatConversationMessage source = messageRepository
                .findByIdAndFamilyId(seed.messageId(), seed.familyId())
                .orElseThrow();
        assertThat(source.getLastError())
                .isEqualTo("STALE_AI_RESPONSE:STATUS_CHANGED");
    }
    private Seed seed(ConversationStatus status) {
        long bindId = NEXT_ID.incrementAndGet();
        long userId = bindId + 100;
        long familyId = bindId + 200;
        createdBindIds.add(bindId);

        Seed seed = transactionTemplate.execute(transaction -> {
            WechatChatContext context = new WechatChatContext();
            context.setWechatBindId(bindId);
            context.setUserId(userId);
            context.setCurrentFamilyId(familyId);
            context.setLeaseOwner(WORKER_ID);
            context.setLeaseUntil(NOW.plusSeconds(120));
            contextRepository.save(context);

            WechatTaskConversation conversation = new WechatTaskConversation();
            conversation.setFamilyId(familyId);
            conversation.setWechatBindId(bindId);
            conversation.setUserId(userId);
            conversation.setStatus(status);
            conversation.setCurrentRevision(7);
            conversation.setSlotState(Map.of(
                    "notes",
                    Map.of(
                            "value", "保留原槽位",
                            "source", "USER_EXPLICIT",
                            "source_message_id", 99L)));
            conversation.setLastActivityAt(NOW.minusSeconds(60));
            conversation.setExpiresAt(NOW.plus(1, ChronoUnit.HOURS));
            conversation = conversationRepository.save(conversation);

            WechatConversationMessage source = new WechatConversationMessage();
            source.setWechatAccountId("action-test-account");
            source.setExternalMsgId("action-test-message-" + bindId);
            source.setOpenid("action-openid-" + bindId);
            source.setDirection(MessageDirection.INBOUND);
            source.setContent("请帮我安排周五买菜");
            source.setWechatBindId(bindId);
            source.setUserId(userId);
            source.setFamilyId(familyId);
            source.setConversationId(conversation.getId());
            source.setProcessingStatus(MessageProcessingStatus.PROCESSING);
            source.setAttempts(1);
            source.setLeaseOwner(WORKER_ID);
            source.setLeaseUntil(NOW.plusSeconds(120));
            source.setAvailableAt(NOW);
            source.setReceivedAt(NOW);
            source = messageRepository.save(source);
            entityManager.flush();

            MessageLease lease = new MessageLease(
                    source.getId(),
                    bindId,
                    WORKER_ID,
                    NOW.plusSeconds(120),
                    1);
            return new Seed(
                    bindId,
                    userId,
                    familyId,
                    conversation.getId(),
                    source.getId(),
                    status,
                    lease);
        });
        if (seed == null) {
            throw new IllegalStateException("测试数据创建失败");
        }
        return seed;
    }

    private PreparedTurn turn(Seed seed) {
        TurnRequest request = new TurnRequest(
                seed.conversationId(),
                0L,
                7,
                seed.messageId(),
                "请帮我安排周五买菜",
                new FamilyContext(
                        seed.familyId(),
                        "动作测试家庭",
                        seed.userId(),
                        FamilyRole.ADULT),
                List.of("title", "priority", "notes"),
                Map.of(),
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of());
        return new PreparedTurn(
                seed.lease(),
                WechatConversationCommandParser.ParsedCommand.text(
                        "请帮我安排周五买菜"),
                null,
                seed.userId(),
                seed.familyId(),
                "动作测试家庭",
                seed.conversationId(),
                null,
                request,
                seed.status(),
                null);
    }

    private TurnCallResult result(
            ActionType actionType,
            String reply,
            Map<String, SlotValue> patch) {
        return TurnCallResult.accepted(
                new TurnAction(actionType, reply, List.of(), patch));
    }

    private Snapshot snapshot(Seed seed) {
        Snapshot value = transactionTemplate.execute(transaction -> {
            WechatConversationMessage source = messageRepository
                    .findByIdAndFamilyId(seed.messageId(), seed.familyId())
                    .orElseThrow();
            WechatTaskConversation conversation = conversationRepository
                    .findByIdAndFamilyId(seed.conversationId(), seed.familyId())
                    .orElseThrow();
            WechatChatContext context = contextRepository
                    .findByWechatBindId(seed.bindId())
                    .orElseThrow();
            return new Snapshot(
                    source.getProcessingStatus(),
                    source.getLeaseOwner(),
                    context.getLeaseOwner(),
                    conversation.getStatus(),
                    conversation.getCurrentRevision(),
                    conversation.getSlotState());
        });
        if (value == null) {
            throw new IllegalStateException("测试快照读取失败");
        }
        return value;
    }

    private int outboundCount(long bindId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from wechat_conversation_messages "
                        + "where wechat_bind_id = ? and direction = 'OUTBOUND'",
                Integer.class,
                bindId);
        return count == null ? 0 : count;
    }

    private Map<?, ?> slot(Map<String, Object> state, String key) {
        assertThat(state.get(key)).isInstanceOf(Map.class);
        return (Map<?, ?>) state.get(key);
    }

    private record Seed(
            long bindId,
            long userId,
            long familyId,
            long conversationId,
            long messageId,
            ConversationStatus status,
            MessageLease lease) {
    }

    private record Snapshot(
            MessageProcessingStatus sourceStatus,
            String sourceLeaseOwner,
            String contextLeaseOwner,
            ConversationStatus status,
            int currentRevision,
            Map<String, Object> slotState) {
    }
}

