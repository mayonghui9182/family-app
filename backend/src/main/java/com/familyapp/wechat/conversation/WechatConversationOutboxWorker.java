package com.familyapp.wechat.conversation;

import com.familyapp.family.FamilyRepository;
import com.familyapp.wechat.WechatBindRepository;
import com.familyapp.wechat.WechatNotifyClient;
import com.familyapp.wechat.WechatNotifyDtos.WechatNotifyItemResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 同会话有序投递；领取与结果记录为短事务，微信 HTTP 在两者之间执行。 */
@Component
public class WechatConversationOutboxWorker {
    @org.springframework.beans.factory.annotation.Autowired
    private WechatConversationOperationalMetrics metrics;
    private static final Logger log = LoggerFactory.getLogger(WechatConversationOutboxWorker.class);
    private static final Set<Integer> TERMINAL_CODES = Set.of(45015, 43004);
    private final WechatMessageOutboxRepository outbox;
    private final WechatConversationMessageRepository messages;
    private final WechatTaskConversationRepository conversations;
    private final WechatBindRepository binds;
    private final FamilyRepository families;
    private final WechatFamilyAccessGuard access;
    private final WechatNotifyClient client;
    private final WechatConversationProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean notifyEnabled;
    private final String owner = UUID.randomUUID().toString();

    public WechatConversationOutboxWorker(WechatMessageOutboxRepository outbox,
            WechatConversationMessageRepository messages, WechatTaskConversationRepository conversations,
            WechatBindRepository binds, FamilyRepository families, WechatFamilyAccessGuard access,
            WechatNotifyClient client, WechatConversationProperties properties,
            PlatformTransactionManager manager, Clock clock,
            @Value("${wechat.notify.enabled:true}") boolean notifyEnabled) {
        this.outbox = outbox;
        this.messages = messages;
        this.conversations = conversations;
        this.binds = binds;
        this.families = families;
        this.access = access;
        this.client = client;
        this.properties = properties;
        this.tx = new TransactionTemplate(manager);
        this.clock = clock;
        this.notifyEnabled = notifyEnabled;
    }

    @Scheduled(fixedDelayString = "${wechat.conversation.poll-fixed-delay-ms:1000}", initialDelay = 15000)
    public void dispatch() {
        if (!properties.isEnabled() || !properties.isWorkerEnabled() || !notifyEnabled) return;
        for (Long familyId : families.findAllActiveIds()) dispatchFamily(familyId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void dispatchFamily(Long familyId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        for (var candidate : outbox.due(familyId, now, PageRequest.of(0, 20))) {
            try {
                Delivery delivery = tx.execute(status -> claim(familyId, candidate.getId(), now));
                if (delivery == null) continue;
                WechatNotifyItemResult result;
                try {
                    result = client.sendKefuTextDetailed(delivery.openid(), delivery.content());
                } catch (RuntimeException exception) {
                    result = new WechatNotifyItemResult(null, false, false, null, "TRANSPORT_ERROR");
                }
                WechatNotifyItemResult sent = result;
                tx.executeWithoutResult(status -> finish(familyId, delivery, sent,
                        clock.instant().truncatedTo(ChronoUnit.MILLIS)));
            } catch (RuntimeException exception) {
                log.warn("客服 outbox 投递失败 family_id={} outbox_id={} error_type={}",
                        familyId, candidate.getId(), exception.getClass().getSimpleName());
            }
        }
    }

    private Delivery claim(Long familyId, Long id, Instant now) {
        var row = outbox.findForUpdate(id, familyId).orElse(null);
        if (row == null || (row.getStatus() != OutboxStatus.PENDING && row.getStatus() != OutboxStatus.SENDING)
                || row.getNotBefore().isAfter(now)
                || (row.getNextRetryAt() != null && row.getNextRetryAt().isAfter(now))
                || (row.getLeaseUntil() != null && row.getLeaseUntil().isAfter(now))
                || outbox.earlierPending(familyId, row.getConversationId(), row.getSequenceNo()) > 0) return null;
        if (row.getAttempts() >= properties.getMaxAttempts()) {
            fail(row, "ATTEMPTS_EXHAUSTED");
            return null;
        }
        var message = messages.findByIdAndFamilyId(row.getMessageId(), familyId).orElse(null);
        var conversation = conversations.findByIdAndFamilyId(row.getConversationId(), familyId).orElse(null);
        var bind = message == null || message.getWechatBindId() == null ? null
                : binds.findById(message.getWechatBindId()).orElse(null);
        if (message == null || conversation == null || conversation.getDeletedAt() != null
                || message.getDirection() != MessageDirection.OUTBOUND
                || !Objects.equals(message.getConversationId(), row.getConversationId())
                || bind == null || !Objects.equals(bind.getUser().getId(), message.getUserId())
                || !Objects.equals(bind.getOpenid(), message.getOpenid())
                || !Objects.equals(conversation.getUserId(), message.getUserId())
                || !Objects.equals(conversation.getWechatBindId(), bind.getId())
                || access.findAccess(message.getUserId(), familyId).isEmpty()) {
            fail(row, "RECIPIENT_UNAVAILABLE");
            return null;
        }
        row.setStatus(OutboxStatus.SENDING);
        row.setAttempts(row.getAttempts() + 1);
        row.setLeaseOwner(owner);
        row.setLeaseUntil(now.plusSeconds(properties.getLeaseSeconds()));
        outbox.save(row);
        return new Delivery(row.getId(), row.getLeaseUntil(), message.getOpenid(), message.getContent());
    }

    private void finish(Long familyId, Delivery delivery, WechatNotifyItemResult result, Instant now) {
        var row = outbox.findForUpdate(delivery.id(), familyId).orElse(null);
        if (row == null || row.getStatus() != OutboxStatus.SENDING
                || !owner.equals(row.getLeaseOwner()) || !delivery.leaseUntil().equals(row.getLeaseUntil())) return;
        row.setLeaseOwner(null);
        row.setLeaseUntil(null);
        row.setWechatErrorCode(result == null || result.errcode() == null ? null : result.errcode().toString());
        if (metrics != null) metrics.outbox(result == null ? null : result.errcode());
        if (result != null && result.ok()) {
            row.setStatus(OutboxStatus.SENT);
            row.setSentAt(now);
            row.setLastError(null);
            row.setNextRetryAt(null);
        } else if ((result != null && result.errcode() != null && TERMINAL_CODES.contains(result.errcode()))
                || row.getAttempts() >= properties.getMaxAttempts() || (result != null && Boolean.TRUE.equals(result.skipped()))) {
            fail(row, "WECHAT_REJECTED");
        } else {
            row.setStatus(OutboxStatus.PENDING);
            long delay = Math.min(properties.getRetryMaxDelayMs(),
                    properties.getRetryInitialDelayMs() * (1L << Math.min(row.getAttempts() - 1, 20)));
            row.setNextRetryAt(now.plusMillis(delay));
            row.setLastError("SEND_RETRYABLE");
        }
        outbox.save(row);
    }

    private void fail(WechatMessageOutbox row, String reason) {
        row.setStatus(OutboxStatus.FAILED);
        row.setLeaseOwner(null);
        row.setLeaseUntil(null);
        row.setNextRetryAt(null);
        row.setLastError(reason);
        outbox.save(row);
    }

    private record Delivery(Long id, Instant leaseUntil, String openid, String content) { }
}
