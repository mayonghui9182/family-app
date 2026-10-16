package com.familyapp.wechat;

import com.familyapp.config.FeatureGuardConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * 入站队列轮询作业（Change 14 Task 3）：消费 {@code wechat_inbound_message} 的 PENDING 行。
 *
 * <p>设计要点（design.md D2/D8）：
 * <ul>
 *   <li><b>表即队列</b>：不引入 {@code @Async}、不额外建消息中间件，捞取用固定延迟轮询，
 *       重启不丢队列、排障时直接查表即可看到每一条的下场；</li>
 *   <li><b>事务绝不跨外呼</b>：抢占、重读、落终态各自是一个由 {@link TransactionTemplate}
 *       开启的独立小事务，提交后才调用建草稿/拆解。LLM 调用是数十秒级，
 *       若把数据库事务横跨其上，连接池会被长调用抽干；</li>
 *   <li><b>逐条隔离</b>：单条失败只影响该行状态，本批其余行继续处理；</li>
 *   <li><b>开关双层</b>：总开关 {@code wechat.intake.enabled} 关闭时整批零动作（连捞都不捞），
 *       AI 开关 {@code familyapp.ai-decompose.enabled} 关闭时逐条标 {@code SKIPPED/ai_disabled}
 *       留痕——两者语义不同，前者是「别碰这条链路」，后者是「这条消息我们看到了但不拆」。</li>
 * </ul>
 *
 * <p>前置条件：{@code application.yml} 已把调度线程池扩到 4。Spring Boot 默认只有 1 个调度线程，
 * 本仓库既存 6 处 {@code @Scheduled}，加上本作业的长调用会把定时任务全部堵死。
 */
@Component
public class WechatIntakeJob {

    private static final Logger log = LoggerFactory.getLogger(WechatIntakeJob.class);

    /** 单批上限：与 {@code findTop20} 的既有风格一致，避免一次捞太多把线程池占满整个 LLM 时长。 */
    static final int BATCH_SIZE = 20;

    /** AI 开关关闭时的 skip_reason 契约值（wechat-service 据此挑话术）。 */
    static final String SKIP_REASON_AI_DISABLED = "ai_disabled";

    /** last_error 列宽（V12），异常摘要超长即截断。 */
    private static final int LAST_ERROR_MAX_LENGTH = 500;

    private final WechatInboundMessageRepository repository;
    private final WechatIntakeService intakeService;
    private final FeatureGuardConfig aiGuard;
    private final TransactionTemplate tx;
    private final boolean intakeEnabled;
    private final int maxAttempts;

    public WechatIntakeJob(WechatInboundMessageRepository repository,
                           WechatIntakeService intakeService,
                           FeatureGuardConfig aiGuard,
                           PlatformTransactionManager txManager,
                           @Value("${wechat.intake.enabled:false}") boolean intakeEnabled,
                           @Value("${wechat.intake.max-attempts:3}") int maxAttempts) {
        this.repository = repository;
        this.intakeService = intakeService;
        this.aiGuard = aiGuard;
        this.tx = new TransactionTemplate(txManager);
        this.intakeEnabled = intakeEnabled;
        this.maxAttempts = maxAttempts;
    }

    /**
     * 轮询入口。默认每 5s 一批，启动后延迟 15s 再开跑——
     * 让 Flyway、连接池、微信 access_token 预热先完成，避免冷启动那一轮白白吃掉一次重试额度。
     */
    @Scheduled(fixedDelayString = "${wechat.intake.poll-fixed-delay-ms:5000}",
               initialDelayString = "${wechat.intake.poll-initial-delay-ms:15000}")
    public void pollInboundQueue() {
        if (!intakeEnabled) {
            return;
        }
        Pageable batchPage = PageRequest.of(0, BATCH_SIZE);
        List<WechatInboundMessage> batch = tx.execute(status -> repository.findProcessable(batchPage, maxAttempts));
        if (batch == null || batch.isEmpty()) {
            return;
        }
        log.info("入站队列轮询开始：本批 {} 行", batch.size());
        for (WechatInboundMessage row : batch) {
            try {
                处理单条(row);
            } catch (RuntimeException e) {
                // 兜底隔离：状态迁移本身失败（如数据库抖动）也只记日志，绝不中断本批其余行
                log.error("入站消息处理异常：msgId={}", row.getMsgId(), e);
            }
        }
        log.info("入站队列轮询结束：本批 {} 行", batch.size());
    }

    /**
     * 处理单行：判开关 → 判归属 → CAS 抢占 → 外呼建草稿并拆解 → 落终态 → （仅终态）回推话术。
     *
     * <p>抢占成功后的外呼是一次「建草稿 + A-2 拆解」的编排（{@code decomposeInbound}），
     * 失败时行上的 {@code session_id} 可能已经挂上草稿——这是有意为之，回队重跑会复用它。
     *
     * <p>前置判定都放在抢占之前，因为抢占会消耗一次 attempts；
     * 「AI 关着」或「没有 family_id」这类情况重试一万次结果也一样，不该占额度。
     */
    private void 处理单条(WechatInboundMessage row) {
        String msgId = row.getMsgId();

        if (!aiGuard.isAiDecomposeEnabled()) {
            tx.executeWithoutResult(status -> repository.markSkipped(msgId, SKIP_REASON_AI_DISABLED, Instant.now()));
            log.info("AI 拆解已关闭，跳过入站消息：msgId={}", msgId);
            // 行已落终态，此时才回推：让用户知道这条消息「看到了但不拆」，并给一条手动添加的替代路（D8）
            回推("AI关闭提示", msgId, () -> intakeService.notifyAiDisabled(row));
            return;
        }
        // 硬约束 4（family_id 多租户隔离）：归属缺失时任何猜测都可能把任务写进别人的家庭 ⇒ 宁拒不猜
        if (row.getFamilyId() == null) {
            tx.executeWithoutResult(status ->
                repository.markFailed(msgId, "family_id 缺失，无法确定家庭归属，已拒绝自动拆解", Instant.now()));
            log.warn("入站消息缺少 family_id，拒绝处理：msgId={}", msgId);
            return;
        }

        WechatInboundMessage claimed = tx.execute(status ->
            repository.claim(msgId, maxAttempts, Instant.now()) == 0
                ? null
                : repository.findByMsgId(msgId).orElse(null));
        if (claimed == null) {
            log.debug("入站消息未被本线程抢占（他人已取走或已终态）：msgId={}", msgId);
            return;
        }

        Long sessionId = null;
        boolean 已转人工 = false;
        try {
            Long 本次草稿 = intakeService.decomposeInbound(claimed);
            tx.executeWithoutResult(status -> repository.markDone(msgId, 本次草稿, Instant.now()));
            log.info("入站消息拆解成功：msgId={} sessionId={}", msgId, 本次草稿);
            // 终态事务已提交，之后没有任何事情能再动摇它；把结果留到这里决定回推哪种话术
            sessionId = 本次草稿;
        } catch (RuntimeException e) {
            String error = 错误摘要(e);
            int attempts = claimed.getAttempts();
            if (attempts >= maxAttempts) {
                tx.executeWithoutResult(status -> repository.markFailed(msgId, error, Instant.now()));
                log.error("入站消息拆解失败且重试耗尽，转人工：msgId={} attempts={}", msgId, attempts);
                已转人工 = true;
            } else {
                tx.executeWithoutResult(status -> repository.markRetry(msgId, error, Instant.now()));
                log.warn("入站消息拆解失败，回队等下轮：msgId={} attempts={}/{}", msgId, attempts, maxAttempts);
            }
        }

        // 回推全部放在 try/catch 之外：一旦被上面那个 catch 当成「拆解失败」，
        // 就会把已经 DONE 的行改回 PENDING 甚至 FAILED，用户确认完还被再拆一遍（spec.md 硬条款）。
        // 终态才开口——中间轮次回 PENDING 保持静默，等下轮或最终定局再说话。
        if (sessionId != null) {
            Long 已建草稿 = sessionId;
            回推("确认链接", msgId, () -> intakeService.notifyDone(claimed, 已建草稿));
        } else if (已转人工) {
            回推("兜底话术", msgId, () -> intakeService.notifyFailed(claimed));
        }
    }

    /**
     * 回推守卫（D6 失败隔离的作业侧落点）：通知是旁路，任何异常都只记 error。
     *
     * <p>本批其余行照常处理，行状态也绝不因回推动摇——此刻终态事务已经提交，
     * 再没有能改它的事情发生。
     */
    private void 回推(String 类型, String msgId, Runnable 动作) {
        try {
            动作.run();
        } catch (RuntimeException e) {
            log.error("入站消息回推{}失败（不影响已落的终态）：msgId={}", 类型, msgId, e);
        }
    }

    /** 异常摘要：类名 + message，压成单行并按列宽截断，避免堆栈污染 last_error。 */
    private String 错误摘要(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getName() : e.getClass().getSimpleName() + ": " + e.getMessage();
        String singleLine = message.replaceAll("\\s+", " ").strip();
        return singleLine.length() <= LAST_ERROR_MAX_LENGTH
            ? singleLine
            : singleLine.substring(0, LAST_ERROR_MAX_LENGTH);
    }
}
