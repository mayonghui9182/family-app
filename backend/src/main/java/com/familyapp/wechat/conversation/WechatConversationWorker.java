package com.familyapp.wechat.conversation;

import com.familyapp.wechat.conversation.WechatConversationAgentClient.ActionType;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnCallResult;
import com.familyapp.wechat.conversation.WechatConversationLeaseService.MessageLease;
import com.familyapp.wechat.conversation.WechatConversationLocalCommandHandler.LocalCommandResult;
import com.familyapp.wechat.conversation.WechatConversationTurnPreparationService.PreparedTurn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 微信持续会话表队列 worker。
 *
 * <p>抢占、准备和本地命令完成由各自短事务服务负责；本类本身不启事务，
 * 因此 agent-service HTTP 调用发生时不会占用数据库连接或持有行锁。</p>
 */
@Component
public class WechatConversationWorker {

    static final int BATCH_SIZE = 20;

    private static final Logger LOG =
            LoggerFactory.getLogger(WechatConversationWorker.class);

    private final WechatConversationProperties properties;
    private final WechatConversationLeaseService leaseService;
    private final WechatConversationFailureService failureService;
    private final WechatConversationTurnPreparationService preparationService;
    private final WechatConversationCandidateActionService candidateActionService;
    private final WechatConversationLocalCommandHandler localCommandHandler;
    private final WechatConversationLocalCompletionService completionService;
    private final WechatConversationNonCandidateActionService actionService;
    private final WechatConversationAgentClient agentClient;
    private final WechatConversationProgressService progress;
    private final Clock clock;
    private final String workerId;

    @Autowired
    public WechatConversationWorker(
            WechatConversationProperties properties,
            WechatConversationLeaseService leaseService,
            WechatConversationFailureService failureService,
            WechatConversationTurnPreparationService preparationService,
            WechatConversationLocalCommandHandler localCommandHandler,
            WechatConversationLocalCompletionService completionService,
            WechatConversationNonCandidateActionService actionService,
            WechatConversationCandidateActionService candidateActionService,
            WechatConversationAgentClient agentClient,
            WechatConversationProgressService progress,
            Clock clock) {
        this(
                properties,
                leaseService,
                failureService,
                preparationService,
                localCommandHandler,
                completionService,
                actionService,
                candidateActionService,
                agentClient,
                progress,
                clock,
                "conversation-" + UUID.randomUUID());
    }

    WechatConversationWorker(
            WechatConversationProperties properties,
            WechatConversationLeaseService leaseService,
            WechatConversationFailureService failureService,
            WechatConversationTurnPreparationService preparationService,
            WechatConversationLocalCommandHandler localCommandHandler,
            WechatConversationLocalCompletionService completionService,
            WechatConversationNonCandidateActionService actionService,
            WechatConversationCandidateActionService candidateActionService,
            WechatConversationAgentClient agentClient,
            WechatConversationProgressService progress,
            Clock clock,
            String workerId) {
        this.properties = properties;
        this.leaseService = leaseService;
        this.failureService = failureService;
        this.preparationService = preparationService;
        this.localCommandHandler = localCommandHandler;
        this.completionService = completionService;
        this.actionService = actionService;
        this.candidateActionService = candidateActionService;
        this.agentClient = agentClient;
        this.progress = progress;
        this.clock = clock;
        this.workerId = workerId;
    }

    @Scheduled(
            fixedDelayString = "${wechat.conversation.poll-fixed-delay-ms:1000}",
            initialDelayString = "${wechat.conversation.poll-initial-delay-ms:15000}")
    public void pollConversationQueue() {
        if (!properties.isEnabled() || !properties.isWorkerEnabled()) {
            return;
        }
        runBatch(clock.instant());
    }

    int runBatch(Instant now) {
        List<Long> bindIds =
                leaseService.findProcessableWechatBindIds(now, BATCH_SIZE);
        int claimed = 0;
        for (Long bindId : bindIds) {
            try {
                ProcessingResult result = processBinding(bindId, workerId, now);
                if (result.outcome() != ProcessingOutcome.NOT_CLAIMED) {
                    claimed++;
                }
            } catch (RuntimeException exception) {
                // 单绑定异常只等待租约过期接管，不中断本批，也不记录正文或 openid。
                LOG.error(
                        "微信会话绑定处理失败 bind_id={} error_type={}",
                        bindId,
                        exception.getClass().getSimpleName());
            }
        }
        return claimed;
    }

    ProcessingResult processBinding(
            long wechatBindId,
            String claimWorkerId,
            Instant now) {
        Optional<MessageLease> claimed =
                leaseService.claimNext(wechatBindId, claimWorkerId, now);
        if (claimed.isEmpty()) {
            return ProcessingResult.notClaimed();
        }

        MessageLease lease = claimed.get();
        TurnCallResult callResult;
        try {
            PreparedTurn turn = preparationService.prepare(lease, now);
            if (!turn.requiresAi()) {
                LocalCommandResult commandResult =
                        localCommandHandler.handle(turn, clock.instant());
                completionService.complete(lease, commandResult, clock.instant());
                return ProcessingResult.localCompleted();
            }

            progress.start(lease, turn, clock.instant());
            try {
                callResult = agentClient.turn(turn.turnRequest());
            } finally {
                progress.finish(turn);
            }
            if (callResult.accepted()
                    && (callResult.action().action() == ActionType.ASK_CLARIFICATION
                    || callResult.action().action() == ActionType.REFUSE_OR_HELP)) {
                var applied = actionService.apply(
                        lease, turn, callResult, clock.instant());
                if (applied.stale()) {
                    return ProcessingResult.aiResponseStale(callResult);
                }
                return ProcessingResult.aiActionCompleted(callResult);
            }
            if (callResult.accepted()
                    && callResult.action().action() == ActionType.PRESENT_CANDIDATE) {
                var applied = candidateActionService.apply(
                        lease, turn, callResult, clock.instant());
                if (applied.stale()) {
                    return ProcessingResult.aiResponseStale(callResult);
                }
                return ProcessingResult.aiCandidateCompleted(callResult);
            }
            if (callResult.accepted()) {
                throw new IllegalStateException("Agent 返回了未支持的动作");
            }
        } catch (RuntimeException exception) {
            LOG.warn(
                    "微信会话消息处理异常 bind_id={} message_id={} error_type={}",
                    lease.wechatBindId(),
                    lease.messageId(),
                    exception.getClass().getSimpleName());
            return recordFailure(lease, null, "PROCESSING_EXCEPTION");
        }

        return recordFailure(
                lease, callResult, failureReason(callResult.failureKind()));
    }


    private ProcessingResult recordFailure(
            MessageLease lease,
            TurnCallResult callResult,
            String reason) {
        var failure = failureService.recordFailure(
                lease, reason, clock.instant());
        if (failure.outcome()
                == WechatConversationFailureService.FailureOutcome.FAILED) {
            return ProcessingResult.aiFailed(callResult);
        }
        return ProcessingResult.aiRetryScheduled(callResult);
    }

    private String failureReason(
            WechatConversationAgentClient.FailureKind failureKind) {
        return switch (failureKind) {
            case INVALID_REQUEST -> "AI_INVALID_REQUEST";
            case TIMEOUT -> "AI_TIMEOUT";
            case REMOTE_FAILURE -> "AI_REMOTE_FAILURE";
            case INVALID_RESPONSE -> "AI_INVALID_RESPONSE";
            case NONE -> throw new IllegalArgumentException(
                    "成功响应不能进入失败处理");
        };
    }

    enum ProcessingOutcome {
        NOT_CLAIMED,
        LOCAL_COMPLETED,
        AI_ACTION_COMPLETED,
        AI_CANDIDATE_COMPLETED,
        AI_RESPONSE_STALE,
        AI_RETRY_SCHEDULED,
        AI_FAILED,
        AI_DISPATCHED
    }

    record ProcessingResult(
            ProcessingOutcome outcome,
            TurnCallResult callResult) {

        static ProcessingResult notClaimed() {
            return new ProcessingResult(ProcessingOutcome.NOT_CLAIMED, null);
        }

        static ProcessingResult localCompleted() {
            return new ProcessingResult(ProcessingOutcome.LOCAL_COMPLETED, null);
        }

        static ProcessingResult aiActionCompleted(TurnCallResult callResult) {
            return new ProcessingResult(
                    ProcessingOutcome.AI_ACTION_COMPLETED, callResult);
        }

        static ProcessingResult aiCandidateCompleted(TurnCallResult callResult) {
            return new ProcessingResult(
                    ProcessingOutcome.AI_CANDIDATE_COMPLETED, callResult);
        }

        static ProcessingResult aiResponseStale(TurnCallResult callResult) {
            return new ProcessingResult(
                    ProcessingOutcome.AI_RESPONSE_STALE, callResult);
        }

        static ProcessingResult aiDispatched(TurnCallResult callResult) {
            return new ProcessingResult(
                    ProcessingOutcome.AI_DISPATCHED, callResult);
        }

        static ProcessingResult aiRetryScheduled(TurnCallResult callResult) {
            return new ProcessingResult(
                    ProcessingOutcome.AI_RETRY_SCHEDULED, callResult);
        }

        static ProcessingResult aiFailed(TurnCallResult callResult) {
            return new ProcessingResult(
                    ProcessingOutcome.AI_FAILED, callResult);
        }
    }
}
