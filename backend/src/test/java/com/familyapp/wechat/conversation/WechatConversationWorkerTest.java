package com.familyapp.wechat.conversation;

import com.familyapp.family.FamilyRole;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.ActionType;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.FailureKind;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.FamilyContext;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnAction;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnCallResult;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnRequest;
import com.familyapp.wechat.conversation.WechatConversationCommandParser.CommandType;
import com.familyapp.wechat.conversation.WechatConversationCommandParser.ParsedCommand;
import com.familyapp.wechat.conversation.WechatConversationLeaseService.MessageLease;
import com.familyapp.wechat.conversation.WechatConversationLocalCommandHandler.LocalCommandResult;
import com.familyapp.wechat.conversation.WechatConversationTurnPreparationService.PreparedTurn;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class WechatConversationWorkerTest {

    private static final AtomicLong NEXT_BIND_ID = new AtomicLong(9_200_000L);
    private static final Instant NOW = Instant.parse("2026-10-02T09:00:00Z");

    @Autowired
    private WechatConversationWorker worker;

    @Autowired
    private WechatChatContextRepository contextRepository;

    @Autowired
    private WechatConversationMessageRepository messageRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private WechatConversationTurnPreparationService preparationService;

    @MockBean
    private WechatConversationLocalCommandHandler localCommandHandler;

    @MockBean
    private WechatConversationNonCandidateActionService actionService;
    @MockBean
    private WechatConversationCandidateActionService candidateActionService;


    @MockBean
    private WechatConversationAgentClient agentClient;

    @MockBean
    private WechatConversationProgressService progress;

    private final List<Long> createdBindIds = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanUp() {
        reset(preparationService, localCommandHandler, actionService,
                candidateActionService, agentClient);
        for (Long bindId : createdBindIds) {
            jdbcTemplate.update(
                    "delete from wechat_conversation_messages where wechat_bind_id = ?",
                    bindId);
            jdbcTemplate.update(
                    "delete from wechat_chat_context where wechat_bind_id = ?",
                    bindId);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = CommandType.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = "ORDINARY_TEXT")
    void everyDeterministicCommandStaysLocal(CommandType commandType) {
        long bindId = seedBindingWithMessage("本地命令");
        ParsedCommand command = new ParsedCommand(commandType, 1, "一一家");

        when(preparationService.prepare(any(MessageLease.class), eq(NOW)))
                .thenAnswer(invocation -> {
                    MessageLease lease = invocation.getArgument(0);
                    return transactionTemplate.execute(status -> localTurn(lease, command));
                });
        when(localCommandHandler.handle(any(PreparedTurn.class), any(Instant.class)))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager
                            .isActualTransactionActive()).isFalse();
                    return new LocalCommandResult("本地处理完成", null, null);
                });

        var result = worker.processBinding(bindId, "worker-local", NOW);

        assertThat(result.outcome())
                .isEqualTo(WechatConversationWorker.ProcessingOutcome.LOCAL_COMPLETED);
        verify(agentClient, never()).turn(any());
        MessageSnapshot source = readInbound(bindId);
        assertThat(source.status()).isEqualTo(MessageProcessingStatus.PROCESSED);
        assertThat(source.leaseOwner()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wechat_conversation_messages "
                        + "where wechat_bind_id = ? and direction = 'OUTBOUND'",
                Integer.class,
                bindId)).isEqualTo(1);
    }

    @Test
    void agentHttpCallRunsAfterLeaseAndPreparationTransactionsCommit() {
        long bindId = seedBindingWithMessage("请帮我安排明天买菜");

        when(preparationService.prepare(any(MessageLease.class), eq(NOW)))
                .thenAnswer(invocation -> {
                    MessageLease lease = invocation.getArgument(0);
                    return transactionTemplate.execute(status -> aiTurn(lease));
                });
        when(agentClient.turn(any(TurnRequest.class)))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager
                            .isActualTransactionActive()).isFalse();
                    return TurnCallResult.fallback(FailureKind.TIMEOUT);
                });

        var result = worker.processBinding(bindId, "worker-ai", NOW);

        assertThat(result.outcome())
                .isEqualTo(
                        WechatConversationWorker.ProcessingOutcome.AI_RETRY_SCHEDULED);
        assertThat(result.callResult().failureKind()).isEqualTo(FailureKind.TIMEOUT);
        verify(localCommandHandler, never()).handle(any(), any());
        MessageSnapshot source = readInbound(bindId);
        assertThat(source.status()).isEqualTo(MessageProcessingStatus.PENDING);
        assertThat(source.attempts()).isEqualTo(1);
        assertThat(source.availableAt()).isAfter(NOW);
        assertThat(source.leaseOwner()).isNull();
        assertThat(source.lastError()).isEqualTo("AI_TIMEOUT");
        assertThat(source.processedAt()).isNull();
    }

    @Test
    void terminalFailureOnlyAffectsCurrentMessageAndNextContinuesInOrder() {
        long bindId = seedBindingWithMessage("第一条需要 AI 的消息");
        long firstMessageId = jdbcTemplate.queryForObject(
                "select id from wechat_conversation_messages "
                        + "where wechat_bind_id = ? and direction = 'INBOUND'",
                Long.class,
                bindId);
        jdbcTemplate.update(
                "update wechat_conversation_messages set attempts = 4 where id = ?",
                firstMessageId);
        long secondMessageId = seedAdditionalMessage(
                bindId, "当前家庭", NOW.plusSeconds(1));

        when(preparationService.prepare(any(MessageLease.class), eq(NOW)))
                .thenAnswer(invocation -> {
                    MessageLease lease = invocation.getArgument(0);
                    if (lease.messageId() == firstMessageId) {
                        return transactionTemplate.execute(status -> aiTurn(lease));
                    }
                    return transactionTemplate.execute(status -> localTurn(
                            lease,
                            new ParsedCommand(
                                    CommandType.CURRENT_FAMILY,
                                    null,
                                    null)));
                });
        when(agentClient.turn(any(TurnRequest.class)))
                .thenReturn(TurnCallResult.fallback(FailureKind.TIMEOUT));
        when(localCommandHandler.handle(any(PreparedTurn.class), any(Instant.class)))
                .thenReturn(new LocalCommandResult("当前家庭是测试家庭。", null, null));

        var failed = worker.processBinding(bindId, "worker-last-attempt", NOW);
        var continued = worker.processBinding(bindId, "worker-next", NOW);

        assertThat(failed.outcome())
                .isEqualTo(WechatConversationWorker.ProcessingOutcome.AI_FAILED);
        assertThat(continued.outcome())
                .isEqualTo(WechatConversationWorker.ProcessingOutcome.LOCAL_COMPLETED);

        MessageSnapshot first = readInbound(bindId, firstMessageId);
        assertThat(first.status()).isEqualTo(MessageProcessingStatus.FAILED);
        assertThat(first.attempts()).isEqualTo(5);
        assertThat(first.leaseOwner()).isNull();
        assertThat(first.lastError()).isEqualTo("AI_TIMEOUT");
        assertThat(first.processedAt()).isNotNull();

        MessageSnapshot second = readInbound(bindId, secondMessageId);
        assertThat(second.status()).isEqualTo(MessageProcessingStatus.PROCESSED);
        assertThat(second.attempts()).isEqualTo(1);
        verify(agentClient).turn(any(TurnRequest.class));
    }



    @Test
    void batchLoopDiscoversAndProcessesQueuedBinding() {
        long bindId = seedBindingWithMessage("当前家庭");

        when(preparationService.prepare(any(MessageLease.class), any(Instant.class)))
                .thenAnswer(invocation -> transactionTemplate.execute(status -> {
                    MessageLease lease = invocation.getArgument(0);
                    return localTurn(
                            lease,
                            new ParsedCommand(
                                    CommandType.CURRENT_FAMILY,
                                    null,
                                    null));
                }));
        when(localCommandHandler.handle(any(PreparedTurn.class), any(Instant.class)))
                .thenReturn(new LocalCommandResult("当前家庭是测试家庭。", null, null));

        int processed = worker.runBatch(NOW);

        assertThat(processed).isEqualTo(1);
        assertThat(readInbound(bindId).status())
                .isEqualTo(MessageProcessingStatus.PROCESSED);
        verify(agentClient, never()).turn(any());
    }

    private long seedBindingWithMessage(String content) {
        long bindId = NEXT_BIND_ID.incrementAndGet();
        createdBindIds.add(bindId);
        transactionTemplate.executeWithoutResult(status -> {
            WechatChatContext context = new WechatChatContext();
            context.setWechatBindId(bindId);
            context.setUserId(bindId);
            contextRepository.save(context);

            WechatConversationMessage message = new WechatConversationMessage();
            message.setWechatAccountId("worker-test");
            message.setExternalMsgId("worker-msg-" + bindId);
            message.setOpenid("worker-openid-" + bindId);
            message.setDirection(MessageDirection.INBOUND);
            message.setContent(content);
            message.setWechatBindId(bindId);
            message.setUserId(bindId);
            message.setProcessingStatus(MessageProcessingStatus.PENDING);
            message.setReceivedAt(NOW);
            message.setAvailableAt(NOW);
            messageRepository.save(message);
            entityManager.flush();
        });
        return bindId;
    }

    private long seedAdditionalMessage(
            long bindId,
            String content,
            Instant receivedAt) {
        Long messageId = transactionTemplate.execute(status -> {
            WechatConversationMessage message = new WechatConversationMessage();
            message.setWechatAccountId("worker-test");
            message.setExternalMsgId(
                    "worker-extra-" + bindId + "-" + receivedAt.toEpochMilli());
            message.setOpenid("worker-openid-" + bindId);
            message.setDirection(MessageDirection.INBOUND);
            message.setContent(content);
            message.setWechatBindId(bindId);
            message.setUserId(bindId);
            message.setProcessingStatus(MessageProcessingStatus.PENDING);
            message.setReceivedAt(receivedAt);
            message.setAvailableAt(NOW);
            messageRepository.save(message);
            entityManager.flush();
            return message.getId();
        });
        if (messageId == null) {
            throw new IllegalStateException("测试消息保存失败");
        }
        return messageId;
    }


    private PreparedTurn localTurn(
            MessageLease lease,
            ParsedCommand command) {
        return new PreparedTurn(
                lease,
                command,
                null,
                lease.wechatBindId(),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private PreparedTurn aiTurn(MessageLease lease) {
        TurnRequest request = new TurnRequest(
                1L,
                0L,
                0,
                lease.messageId(),
                "请帮我安排明天买菜",
                new FamilyContext(1L, "测试家庭", 1L, FamilyRole.ADULT),
                List.of(),
                Map.of(),
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of());
        return new PreparedTurn(
                lease,
                ParsedCommand.text("请帮我安排明天买菜"),
                null,
                lease.wechatBindId(),
                1L,
                "测试家庭",
                1L,
                null,
                request,
                ConversationStatus.ACTIVE,
                null);
    }

    private MessageSnapshot readInbound(long bindId) {
        MessageSnapshot value = transactionTemplate.execute(status ->
                jdbcTemplate.queryForObject(
                        "select processing_status, attempts, available_at, "
                                + "lease_owner, last_error, processed_at "
                                + "from wechat_conversation_messages "
                                + "where wechat_bind_id = ? and direction = 'INBOUND'",
                        (resultSet, rowNumber) -> new MessageSnapshot(
                                MessageProcessingStatus.valueOf(
                                        resultSet.getString("processing_status")),
                                resultSet.getInt("attempts"),
                                resultSet.getTimestamp("available_at").toInstant(),
                                resultSet.getString("lease_owner"),
                                resultSet.getString("last_error"),
                                resultSet.getTimestamp("processed_at") == null
                                        ? null
                                        : resultSet.getTimestamp("processed_at").toInstant()),
                        bindId));
        if (value == null) {
            throw new IllegalStateException("测试消息读取失败");
        }
        return value;
    }

    private MessageSnapshot readInbound(long bindId, long messageId) {
        MessageSnapshot value = transactionTemplate.execute(status ->
                jdbcTemplate.queryForObject(
                        "select processing_status, attempts, available_at, "
                                + "lease_owner, last_error, processed_at "
                                + "from wechat_conversation_messages "
                                + "where wechat_bind_id = ? and id = ?",
                        (resultSet, rowNumber) -> new MessageSnapshot(
                                MessageProcessingStatus.valueOf(
                                        resultSet.getString("processing_status")),
                                resultSet.getInt("attempts"),
                                resultSet.getTimestamp("available_at").toInstant(),
                                resultSet.getString("lease_owner"),
                                resultSet.getString("last_error"),
                                resultSet.getTimestamp("processed_at") == null
                                        ? null
                                        : resultSet.getTimestamp("processed_at").toInstant()),
                        bindId,
                        messageId));
        if (value == null) {
            throw new IllegalStateException("测试消息读取失败");
        }
        return value;
    }


    private record MessageSnapshot(
            MessageProcessingStatus status,
            int attempts,
            Instant availableAt,
            String leaseOwner,
            String lastError,
            Instant processedAt) {
    }

    @Test
    void acceptedRefuseActionUsesShortTransactionCommitter() {
        long bindId = seedBindingWithMessage("这周天气怎么样");

        when(preparationService.prepare(any(MessageLease.class), eq(NOW)))
                .thenAnswer(invocation -> {
                    MessageLease lease = invocation.getArgument(0);
                    return transactionTemplate.execute(status -> aiTurn(lease));
                });
        TurnCallResult accepted = TurnCallResult.accepted(new TurnAction(
                ActionType.REFUSE_OR_HELP,
                "我只能协助创建家庭任务。",
                List.of(),
                Map.of()));
        when(agentClient.turn(any(TurnRequest.class))).thenReturn(accepted);

        when(actionService.apply(any(), any(), eq(accepted), any()))
                .thenReturn(WechatConversationNonCandidateActionService
                        .ApplyResult.applied(1L));
        var result = worker.processBinding(bindId, "worker-refuse", NOW);

        assertThat(result.outcome()).isEqualTo(
                WechatConversationWorker.ProcessingOutcome.AI_ACTION_COMPLETED);
        verify(actionService).apply(
                any(MessageLease.class),
                any(PreparedTurn.class),
                eq(accepted),
                any(Instant.class));
        verify(localCommandHandler, never()).handle(any(), any());
    }
    @Test
    void acceptedPresentActionUsesCandidateCommitter() {
        long bindId = seedBindingWithMessage("请帮我安排买菜");

        when(preparationService.prepare(any(MessageLease.class), eq(NOW)))
                .thenAnswer(invocation -> {
                    MessageLease lease = invocation.getArgument(0);
                    return transactionTemplate.execute(status -> aiTurn(lease));
                });
        TurnCallResult accepted = TurnCallResult.accepted(new TurnAction(
                ActionType.PRESENT_CANDIDATE,
                "版本 1：买菜",
                List.of(),
                Map.of()));
        when(agentClient.turn(any(TurnRequest.class))).thenReturn(accepted);

        when(candidateActionService.apply(any(), any(), eq(accepted), any()))
                .thenReturn(WechatConversationCandidateActionService
                        .ApplyResult.applied(1L, 2L, 1));
        var result = worker.processBinding(bindId, "worker-present", NOW);

        assertThat(result.outcome()).isEqualTo(
                WechatConversationWorker.ProcessingOutcome.AI_CANDIDATE_COMPLETED);
        verify(candidateActionService).apply(
                any(MessageLease.class),
                any(PreparedTurn.class),
                eq(accepted),
                any(Instant.class));
        verify(actionService, never()).apply(any(), any(), any(), any());
    }

}
