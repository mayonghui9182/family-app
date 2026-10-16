package com.familyapp.wechat.conversation;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class WechatConversationLeaseServiceTest {

    private static final AtomicLong NEXT_BIND_ID = new AtomicLong(9_100_000L);
    private static final Instant NOW = Instant.parse("2026-10-02T08:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Autowired
    private WechatConversationLeaseService leaseService;

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

    private final List<Long> createdBindIds = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long bindId : createdBindIds) {
            jdbcTemplate.update(
                    "delete from wechat_conversation_messages where wechat_bind_id = ?",
                    bindId);
            jdbcTemplate.update(
                    "delete from wechat_chat_context where wechat_bind_id = ?",
                    bindId);
            jdbcTemplate.update("delete from wechat_bind where id = ?", bindId);
            jdbcTemplate.update("delete from users where id = ?", bindId);
        }
    }


    @Test
    void firstBoundMessageCreatesMissingChatContextBeforeClaim() {
        long bindId = nextBindId();
        jdbcTemplate.update(
                "insert into users(id, email, nickname, password_hash, created_at, updated_at) "
                        + "values (?, ?, ?, ?, current_timestamp, current_timestamp)",
                bindId,
                "lease-" + bindId + "@example.test",
                "租约测试用户",
                "test-hash");
        jdbcTemplate.update(
                "insert into wechat_bind(id, openid, user_id, bound_at) "
                        + "values (?, ?, ?, current_timestamp)",
                bindId,
                "lease-openid-" + bindId,
                bindId);
        long messageId = seedMessage(bindId, NOW, NOW);

        WechatConversationLeaseService.MessageLease lease = leaseService
                .claimNext(bindId, "worker-first", NOW, LEASE, 5)
                .orElseThrow();

        assertThat(lease.messageId()).isEqualTo(messageId);
        assertThat(contextRepository.findByWechatBindId(bindId))
                .get()
                .extracting(WechatChatContext::getUserId)
                .isEqualTo(bindId);
    }

    @Test
    void sameBindingCanOnlyBeClaimedByOneWorker() throws Exception {
        long bindId = nextBindId();
        long messageId = seedBindingWithMessage(bindId, NOW, NOW);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<WechatConversationLeaseService.MessageLease>> first =
                    executor.submit(() -> claimAfterBarrier(
                            barrier, bindId, "worker-a", NOW));
            Future<Optional<WechatConversationLeaseService.MessageLease>> second =
                    executor.submit(() -> claimAfterBarrier(
                            barrier, bindId, "worker-b", NOW));

            List<Optional<WechatConversationLeaseService.MessageLease>> results =
                    List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));

            assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
            MessageSnapshot snapshot = readMessage(bindId, messageId);
            assertThat(snapshot.status()).isEqualTo(MessageProcessingStatus.PROCESSING);
            assertThat(snapshot.attempts()).isEqualTo(1);
            assertThat(snapshot.leaseOwner()).isIn("worker-a", "worker-b");
            assertThat(leaseService.releaseBindingLease(
                    bindId, "intruder", NOW.plusSeconds(1))).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void differentBindingsCanBeClaimedInParallel() throws Exception {
        long firstBindId = nextBindId();
        long secondBindId = nextBindId();
        seedBindingWithMessage(firstBindId, NOW.minusSeconds(2), NOW);
        seedBindingWithMessage(secondBindId, NOW.minusSeconds(1), NOW);

        List<Long> processable = leaseService.findProcessableWechatBindIds(NOW, 100);
        assertThat(processable.indexOf(firstBindId))
                .isGreaterThanOrEqualTo(0)
                .isLessThan(processable.indexOf(secondBindId));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<WechatConversationLeaseService.MessageLease>> first =
                    executor.submit(() -> claimAfterBarrier(
                            barrier, firstBindId, "worker-a", NOW));
            Future<Optional<WechatConversationLeaseService.MessageLease>> second =
                    executor.submit(() -> claimAfterBarrier(
                            barrier, secondBindId, "worker-b", NOW));

            assertThat(first.get(5, TimeUnit.SECONDS)).isPresent();
            assertThat(second.get(5, TimeUnit.SECONDS)).isPresent();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredProcessingLeaseCanBeTakenOverAfterRenewalWindow() {
        long bindId = nextBindId();
        long messageId = seedBindingWithMessage(bindId, NOW, NOW);

        WechatConversationLeaseService.MessageLease first = leaseService
                .claimNext(bindId, "worker-a", NOW, LEASE, 5)
                .orElseThrow();

        assertThat(leaseService.claimNext(
                bindId, "worker-b", NOW.plusSeconds(10), LEASE, 5)).isEmpty();
        assertThat(leaseService.renew(
                first, NOW.plusSeconds(20), LEASE)).isTrue();
        assertThat(leaseService.claimNext(
                bindId, "worker-b", NOW.plusSeconds(31), LEASE, 5)).isEmpty();

        WechatConversationLeaseService.MessageLease recovered = leaseService
                .claimNext(bindId, "worker-b", NOW.plusSeconds(51), LEASE, 5)
                .orElseThrow();

        assertThat(recovered.messageId()).isEqualTo(messageId);
        assertThat(recovered.attempts()).isEqualTo(2);
        assertThat(recovered.workerId()).isEqualTo("worker-b");
        assertThat(leaseService.renew(
                first, NOW.plusSeconds(52), LEASE)).isFalse();
    }

    @Test
    void earliestProcessableMessageIsClaimedFirst() {
        long bindId = nextBindId();
        seedContext(bindId);
        long laterMessageId = seedMessage(
                bindId, NOW.plusSeconds(5), NOW);
        long earlierMessageId = seedMessage(
                bindId, NOW, NOW);

        WechatConversationLeaseService.MessageLease claimed = leaseService
                .claimNext(bindId, "worker-a", NOW, LEASE, 5)
                .orElseThrow();

        assertThat(claimed.messageId()).isEqualTo(earlierMessageId);
        assertThat(readMessage(bindId, laterMessageId).status())
                .isEqualTo(MessageProcessingStatus.PENDING);
    }


    @Test
    void delayedEarlierRetryBlocksLaterMessageUntilItsBackoffExpires() {
        long bindId = nextBindId();
        seedContext(bindId);
        long earlierMessageId =
                seedMessage(bindId, NOW, NOW.plusSeconds(60));
        seedMessage(bindId, NOW.plusSeconds(1), NOW);

        assertThat(leaseService.findProcessableWechatBindIds(NOW, 100))
                .doesNotContain(bindId);
        assertThat(leaseService.claimNext(
                bindId, "worker-too-early", NOW, LEASE, 5)).isEmpty();

        WechatConversationLeaseService.MessageLease claimed = leaseService
                .claimNext(
                        bindId,
                        "worker-after-backoff",
                        NOW.plusSeconds(60),
                        LEASE,
                        5)
                .orElseThrow();

        assertThat(claimed.messageId()).isEqualTo(earlierMessageId);
    }

    @Test
    void exhaustedExpiredMessageBecomesFailedAndNextMessageCanContinue() {
        long bindId = nextBindId();
        seedContext(bindId);
        long exhaustedMessageId = seedMessage(bindId, NOW, NOW);
        long nextMessageId =
                seedMessage(bindId, NOW.plusSeconds(1), NOW);
        jdbcTemplate.update(
                "update wechat_conversation_messages "
                        + "set processing_status = 'PROCESSING', attempts = 5, "
                        + "lease_owner = 'dead-worker', lease_until = ? "
                        + "where id = ?",
                java.sql.Timestamp.from(NOW.minusSeconds(1)),
                exhaustedMessageId);

        assertThat(leaseService.findProcessableWechatBindIds(NOW, 100))
                .contains(bindId);
        WechatConversationLeaseService.MessageLease claimed = leaseService
                .claimNext(bindId, "worker-next", NOW, LEASE, 5)
                .orElseThrow();

        assertThat(claimed.messageId()).isEqualTo(nextMessageId);
        MessageSnapshot exhausted = readMessage(bindId, exhaustedMessageId);
        assertThat(exhausted.status()).isEqualTo(MessageProcessingStatus.FAILED);
        assertThat(exhausted.leaseOwner()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "select last_error from wechat_conversation_messages where id = ?",
                String.class,
                exhaustedMessageId))
                .isEqualTo("LEASE_EXPIRED_ATTEMPTS_EXHAUSTED");
    }

    private Optional<WechatConversationLeaseService.MessageLease> claimAfterBarrier(
            CyclicBarrier barrier,
            long bindId,
            String workerId,
            Instant now) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return leaseService.claimNext(bindId, workerId, now, LEASE, 5);
    }

    private long nextBindId() {
        long bindId = NEXT_BIND_ID.incrementAndGet();
        createdBindIds.add(bindId);
        return bindId;
    }

    private long seedBindingWithMessage(
            long bindId,
            Instant receivedAt,
            Instant availableAt) {
        seedContext(bindId);
        return seedMessage(bindId, receivedAt, availableAt);
    }

    private void seedContext(long bindId) {
        transactionTemplate.executeWithoutResult(status -> {
            WechatChatContext context = new WechatChatContext();
            context.setWechatBindId(bindId);
            context.setUserId(bindId);
            contextRepository.save(context);
            entityManager.flush();
        });
    }

    private long seedMessage(long bindId, Instant receivedAt, Instant availableAt) {
        Long messageId = transactionTemplate.execute(status -> {
            WechatConversationMessage message = new WechatConversationMessage();
            message.setWechatAccountId("lease-test");
            message.setExternalMsgId("lease-msg-" + bindId + "-" + receivedAt.toEpochMilli());
            message.setOpenid("lease-openid-" + bindId);
            message.setDirection(MessageDirection.INBOUND);
            message.setContent("请帮我安排家庭任务");
            message.setWechatBindId(bindId);
            message.setUserId(bindId);
            message.setProcessingStatus(MessageProcessingStatus.PENDING);
            message.setReceivedAt(receivedAt);
            message.setAvailableAt(availableAt);
            messageRepository.save(message);
            entityManager.flush();
            return message.getId();
        });
        if (messageId == null) {
            throw new IllegalStateException("测试消息保存失败");
        }
        return messageId;
    }

    private MessageSnapshot readMessage(long bindId, long messageId) {
        MessageSnapshot snapshot = transactionTemplate.execute(status -> messageRepository
                .findByIdAndWechatBindId(messageId, bindId)
                .map(message -> new MessageSnapshot(
                        message.getProcessingStatus(),
                        message.getAttempts(),
                        message.getLeaseOwner()))
                .orElseThrow());
        if (snapshot == null) {
            throw new IllegalStateException("测试消息读取失败");
        }
        return snapshot;
    }

    private record MessageSnapshot(
            MessageProcessingStatus status,
            int attempts,
            String leaseOwner) {
    }
}
