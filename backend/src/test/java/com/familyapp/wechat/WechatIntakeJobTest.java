package com.familyapp.wechat;

import com.familyapp.config.FeatureGuardConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入站队列轮询作业测试（Change 14 Task 3）。
 *
 * <p>验证 design.md D2/D8 定的状态机：{@code PENDING →（抢占成功）RUNNING → DONE}，
 * 失败回 {@code PENDING} 并累加 attempts，耗尽转 {@code FAILED}，AI 开关关闭转
 * {@code SKIPPED / ai_disabled}。
 *
 * <p>测试形态说明（有意为之）：类上<b>不加</b> {@code @Transactional}，作业用真实 H2 +
 * 真实 {@link PlatformTransactionManager}，只把建草稿/拆解的协作方 {@link WechatIntakeService}
 * 换成 mock。理由是用例⑤ 要证明「RUNNING 已提交」先于外呼——若测试自己套一层回滚事务，
 * 作业的小事务会加入测试事务（REQUIRED），什么都不会提交，该断言就失去意义。
 * 清理靠 {@code @BeforeEach} 全表 DELETE（其余集成测试类均自带 {@code @Transactional} 回滚，不残留 PENDING 行）。
 */
@SpringBootTest
@ActiveProfiles("test")
class WechatIntakeJobTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final long 草稿指针 = 4242L;
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired WechatInboundMessageRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired FeatureGuardConfig aiGuard;
    @Autowired ObjectProvider<TaskScheduler> taskSchedulerProvider;

    private WechatIntakeService intakeService;
    private final AtomicInteger 外呼次数 = new AtomicInteger();
    /** 外呼那一刻库中该行 status 的快照，由 mock 的 Answer 现场读取。 */
    private final AtomicReference<String> 外呼时状态 = new AtomicReference<>();
    /**
     * 「拆解编排」的可替换行为（Task 6b 起作业调 {@code decomposeInbound}），用例改这个字段注入失败场景。
     *
     * <p>为什么不用 Mockito 的重复打桩（{@code when(...).thenThrow(...)} 或
     * {@code doThrow(...).when(mock)}）：mock 上已注册 Answer 后，再次打桩时 Mockito 会先用
     * {@code null} 实参真实触发上一个 Answer（栈里可见 NullResultGuardian → StubbedInvocationMatcher），
     * 于是快照逻辑 NPE。换行为只动字段，一次打桩用到底。
     */
    private Function<WechatInboundMessage, Long> 拆解;

    @BeforeEach
    void resetQueueAndStub() {
        jdbc.update("DELETE FROM wechat_inbound_message");
        外呼次数.set(0);
        外呼时状态.set(null);
        拆解 = this::拆解成功;

        intakeService = mock(WechatIntakeService.class);
        when(intakeService.decomposeInbound(any())).thenAnswer(invocation ->
            拆解.apply(invocation.getArgument(0)));
    }

    /** 默认行为：计数 + 抓「外呼那一刻的 status」快照 + 返回草稿指针。 */
    private Long 拆解成功(WechatInboundMessage row) {
        外呼次数.incrementAndGet();
        外呼时状态.set(jdbc.queryForObject(
            "SELECT status FROM wechat_inbound_message WHERE msg_id = ?", String.class, row.getMsgId()));
        return 草稿指针;
    }

    private WechatIntakeJob job(FeatureGuardConfig guard, boolean intakeEnabled) {
        return new WechatIntakeJob(repository, intakeService, guard, txManager, intakeEnabled, MAX_ATTEMPTS);
    }

    /** 造一行已裁决完成的 PENDING（family_id/user_id 由受理侧写入）。 */
    private String enqueue(int attempts, Long familyId) {
        String msgId = "job-" + SEQ.incrementAndGet();
        WechatInboundMessage row = new WechatInboundMessage();
        row.setMsgId(msgId);
        row.setOpenid("o-job-test");
        row.setContent("周六带爸去复查");
        row.setReceivedAt(Instant.now());
        row.setStatus(WechatInboundStatus.PENDING);
        row.setAttempts(attempts);
        row.setFamilyId(familyId);
        row.setUserId(familyId == null ? null : 1L);
        repository.save(row);
        return msgId;
    }

    private Map<String, Object> rowOf(String msgId) {
        return jdbc.queryForMap(
            "SELECT status, attempts, session_id, skip_reason, last_error FROM wechat_inbound_message WHERE msg_id = ?",
            msgId);
    }

    @Test
    @DisplayName("① 无 PENDING 行 → 零外呼")
    void emptyQueue_doesNotCallService() {
        job(aiGuard, true).pollInboundQueue();
        assertEquals(0, 外呼次数.get());
    }

    @Test
    @DisplayName("② 捞到 1 行 → PENDING→RUNNING→DONE，session_id 回写、attempts+1")
    void pendingRow_transitionsToDoneWithSessionId() {
        String msgId = enqueue(0, 7L);

        job(aiGuard, true).pollInboundQueue();

        Map<String, Object> row = rowOf(msgId);
        assertEquals("DONE", row.get("status"));
        assertEquals(草稿指针, ((Number) row.get("session_id")).longValue());
        assertEquals(1, ((Number) row.get("attempts")).intValue());
        assertNull(row.get("last_error"));
    }

    @Test
    @DisplayName("③ 拆解抛异常且 attempts 未耗尽 → 回 PENDING、attempts+1、留 last_error")
    void failureBelowMaxAttempts_requeuesWithAttemptIncrement() {
        拆解 = row -> { throw new IllegalStateException("拆解服务不可达"); };
        String msgId = enqueue(0, 7L);

        job(aiGuard, true).pollInboundQueue();

        Map<String, Object> row = rowOf(msgId);
        assertEquals("PENDING", row.get("status"));
        assertEquals(1, ((Number) row.get("attempts")).intValue());
        assertNotNull(row.get("last_error"));
        assertTrue(String.valueOf(row.get("last_error")).contains("拆解服务不可达"));
    }

    @Test
    @DisplayName("④ attempts 达上限仍失败 → FAILED + last_error，不再无限重试")
    void exhaustedAttempts_marksFailed() {
        拆解 = row -> { throw new IllegalStateException("拆解服务不可达"); };
        // attempts=2 ⇒ 本次抢占后自增为 3，等于 max-attempts，判定为耗尽
        String msgId = enqueue(MAX_ATTEMPTS - 1, 7L);

        job(aiGuard, true).pollInboundQueue();

        Map<String, Object> row = rowOf(msgId);
        assertEquals("FAILED", row.get("status"));
        assertEquals(MAX_ATTEMPTS, ((Number) row.get("attempts")).intValue());
        assertNotNull(row.get("last_error"));
        assertNull(row.get("session_id"));
    }

    @Test
    @DisplayName("⑤ RUNNING 先提交再外呼（作业不得跨外呼持事务）")
    void claimIsCommittedBeforeExternalCall() {
        enqueue(0, 7L);

        job(aiGuard, true).pollInboundQueue();

        // 读的是另一条连接的已提交数据；若 RUNNING 与外呼同处一个未提交事务，这里会是 PENDING
        assertEquals("RUNNING", 外呼时状态.get());
    }

    @Test
    @DisplayName("⑥ 遗留 RUNNING 行不被重复捞取（attempts 已达上限）")
    void exhaustedRunningRow_isNotReclaimed() {
        String msgId = enqueue(MAX_ATTEMPTS, 7L);
        jdbc.update("UPDATE wechat_inbound_message SET status = 'RUNNING' WHERE msg_id = ?", msgId);

        job(aiGuard, true).pollInboundQueue();

        assertEquals(0, 外呼次数.get());
        assertEquals("RUNNING", rowOf(msgId).get("status"));
    }

    @Test
    @DisplayName("⑦ 总开关关闭 → 整批零动作，行原样留在 PENDING")
    void intakeDisabled_touchesNothing() {
        String msgId = enqueue(0, 7L);

        job(aiGuard, false).pollInboundQueue();

        assertEquals(0, 外呼次数.get());
        Map<String, Object> row = rowOf(msgId);
        assertEquals("PENDING", row.get("status"));
        assertEquals(0, ((Number) row.get("attempts")).intValue());
    }

    @Test
    @DisplayName("⑧ AI 开关关闭 → SKIPPED / ai_disabled，不建草稿不调拆解（D8）")
    void aiDisabled_skipsRowWithoutCallingPipeline() {
        FeatureGuardConfig guard = mock(FeatureGuardConfig.class);
        when(guard.isAiDecomposeEnabled()).thenReturn(false);
        String msgId = enqueue(0, 7L);

        job(guard, true).pollInboundQueue();

        assertEquals(0, 外呼次数.get());
        Map<String, Object> row = rowOf(msgId);
        assertEquals("SKIPPED", row.get("status"));
        assertEquals("ai_disabled", row.get("skip_reason"));
    }

    @Test
    @DisplayName("⑨ PENDING 行缺 family_id → FAILED 并留痕，不猜归属（硬约束 4）")
    void missingFamilyId_failsWithoutGuessing() {
        String msgId = enqueue(0, null);

        job(aiGuard, true).pollInboundQueue();

        assertEquals(0, 外呼次数.get());
        Map<String, Object> row = rowOf(msgId);
        assertEquals("FAILED", row.get("status"));
        assertTrue(String.valueOf(row.get("last_error")).contains("family_id"));
    }

    @Test
    @DisplayName("⑩ 同批两行，第一行抛异常 → 第二行仍处理完成（逐条隔离）")
    void oneFailureDoesNotBlockSameBatch() {
        String first = enqueue(0, 7L);
        String second = enqueue(0, 8L);
        拆解 = row -> {
            外呼次数.incrementAndGet();
            if (first.equals(row.getMsgId())) {
                throw new IllegalStateException("单条失败");
            }
            return 草稿指针;
        };

        job(aiGuard, true).pollInboundQueue();

        assertEquals(2, 外呼次数.get());
        assertEquals("PENDING", rowOf(first).get("status"));
        assertEquals("DONE", rowOf(second).get("status"));
        assertEquals(草稿指针, ((Number) rowOf(second).get("session_id")).longValue());
    }

    /**
     * D2 的硬前置：调度池必须是 4 线程，否则入站作业的 LLM 长调用会堵死既存 6 个 @Scheduled。
     *
     * <p>证伪力已实测：本用例首轮跑出的就是 {@code expected: <4> but was: <1>}——它当场逮到了
     * {@code application.yml} 里 pool.size 配置丢失。注意 {@code assertInstanceOf} 在池大小为
     * 默认 1 时也会通过（Boot 始终提供 ThreadPoolTaskScheduler bean），真正的把关是下面那个数值断言。
     */
    @Test
    @DisplayName("⑪ 调度线程池已扩到 4（D2 硬前置：不得饿死既存 6 个 @Scheduled）")
    void schedulingPoolIsExpanded() {
        TaskScheduler scheduler = taskSchedulerProvider.getIfAvailable();
        assertInstanceOf(ThreadPoolTaskScheduler.class, scheduler, "测试上下文应能取到调度器 bean");
        assertEquals(4, ((ThreadPoolTaskScheduler) scheduler).getPoolSize());
    }

    // ===== Change 14 Task 8：终态迁移之后的回推时机（回推只在事务外、且绝不改动终态）=====

    @Test
    @DisplayName("⑫ 拆解成功转 DONE → 回推确认链接（带 session_id）")
    void doneRow_pushesConfirmationLink() {
        enqueue(0, 7L);

        job(aiGuard, true).pollInboundQueue();

        verify(intakeService).notifyDone(any(), eq(草稿指针));
    }

    @Test
    @DisplayName("⑬ 重试耗尽转 FAILED → 回推「没太看懂」，且不推确认链接")
    void exhaustedRow_pushesFallbackOnly() {
        拆解 = row -> { throw new IllegalStateException("拆解服务不可达"); };
        enqueue(MAX_ATTEMPTS - 1, 7L);

        job(aiGuard, true).pollInboundQueue();

        verify(intakeService).notifyFailed(any());
        verify(intakeService, never()).notifyDone(any(), any());
    }

    @Test
    @DisplayName("⑭ 失败但仍有重试额度 → 零回推（不打断用户，等终态再说）")
    void requeuedRow_pushesNothing() {
        拆解 = row -> { throw new IllegalStateException("拆解服务不可达"); };
        enqueue(0, 7L);

        job(aiGuard, true).pollInboundQueue();

        verify(intakeService, never()).notifyDone(any(), any());
        verify(intakeService, never()).notifyFailed(any());
    }

    @Test
    @DisplayName("⑮ AI 开关关闭转 SKIPPED → 回推「暂时关闭，请手动添加」（D8 明写要回推）")
    void aiDisabledRow_pushesGuidance() {
        FeatureGuardConfig guard = mock(FeatureGuardConfig.class);
        when(guard.isAiDecomposeEnabled()).thenReturn(false);
        enqueue(0, 7L);

        job(guard, true).pollInboundQueue();

        verify(intakeService).notifyAiDisabled(any());
    }

    /**
     * spec.md 的硬条款：「回推失败 SHALL NOT 改变终态」。
     *
     * <p>这里让 {@code notifyDone} 抛异常模拟最坏情况（真实链路上 {@code WechatNotifyClient}
     * 本身永不抛，故这需要作业侧再兜一层）：若回推调用被放在拆解的 try 块里且不加保护，
     * 异常会被外层 catch 吞成「拆解失败」，把已经 DONE 的行改回 PENDING——那才是真事故。
     */
    @Test
    @DisplayName("⑯ 回推抛异常 → 行仍 DONE、attempts 不变（推链接失败不回滚拆解结果）")
    void notifyThrows_doesNotDisturbDoneState() {
        String msgId = enqueue(0, 7L);
        doThrow(new IllegalStateException("回推炸了")).when(intakeService).notifyDone(any(), any());

        job(aiGuard, true).pollInboundQueue();

        Map<String, Object> row = rowOf(msgId);
        assertEquals("DONE", row.get("status"));
        assertEquals(1, ((Number) row.get("attempts")).intValue());
    }
}
