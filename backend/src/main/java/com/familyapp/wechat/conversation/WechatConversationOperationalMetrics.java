package com.familyapp.wechat.conversation;

import com.familyapp.family.FamilyRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.concurrent.atomic.AtomicLong;

/** 固定枚举标签和家庭作用域聚合监控；不暴露用户、正文或家庭标识。 */
@Component("wechatConversationHealthIndicator")
public class WechatConversationOperationalMetrics implements HealthIndicator {
    public enum Event { AI_REJECTED, CONFIRMATION_REPLAY, SUMMARY_REBUILD, CROSS_FAMILY_DENIED, EXPERIENCE_ACTIVATED }
    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private final FamilyRepository families;
    private final WechatConversationProperties properties;
    private final Clock clock;
    private final AtomicLong depth = new AtomicLong();
    private final AtomicLong expired = new AtomicLong();
    private volatile boolean available = true;

    public WechatConversationOperationalMetrics(MeterRegistry registry, JdbcTemplate jdbc,
            FamilyRepository families, WechatConversationProperties properties, Clock clock) {
        this.registry = registry; this.jdbc = jdbc; this.families = families;
        this.properties = properties; this.clock = clock;
        registry.gauge("familyapp.wechat.conversation.queue.depth", depth);
        registry.gauge("familyapp.wechat.conversation.lease.expired", expired);
    }

    public void event(Event event) {
        Runnable action = () -> registry.counter("familyapp.wechat.conversation.operation.total",
                "event", event.name().toLowerCase(java.util.Locale.ROOT)).increment();
        // 安全拒绝通常引起回滚，本身仍必须可观测。
        if (event == Event.CROSS_FAMILY_DENIED) action.run();
        else afterCommit(action);
    }

    public void outbox(Integer errorCode) {
        // 未知平台码折叠，避免任意远程输入产生无界标签。
        String code = errorCode == null ? "transport" : switch (errorCode) {
            case 0, 40001, 40014, 42001, 45015, 43004 -> errorCode.toString();
            default -> "other";
        };
        afterCommit(() -> registry.counter("familyapp.wechat.conversation.outbox.result.total", "code", code).increment());
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        else action.run();
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void refresh() {
        if (!properties.isEnabled()) { depth.set(0); expired.set(0); available = true; return; }
        try {
            long pending = 0, stale = 0;
            for (Long familyId : families.findAllActiveIds()) {
                pending += jdbc.queryForObject("SELECT COUNT(*) FROM wechat_conversation_messages WHERE family_id=? AND direction='INBOUND' AND processing_status IN ('PENDING','PROCESSING','WAITING_FAMILY')", Long.class, familyId);
                stale += jdbc.queryForObject("SELECT COUNT(*) FROM wechat_conversation_messages WHERE family_id=? AND processing_status='PROCESSING' AND lease_until <= ?", Long.class, familyId, Timestamp.from(clock.instant()));
            }
            // 只枚举传输作用域标识；无家庭的绑定引导/待路由审计也计入队列。
            for (String account : jdbc.queryForList("SELECT DISTINCT wechat_account_id FROM wechat_conversation_messages WHERE family_id IS NULL", String.class))
                pending += jdbc.queryForObject("SELECT COUNT(*) FROM wechat_conversation_messages WHERE family_id IS NULL AND wechat_account_id=? AND direction='INBOUND' AND processing_status IN ('PENDING','PROCESSING','WAITING_FAMILY')", Long.class, account);
            depth.set(pending); expired.set(stale); available = true;
        } catch (RuntimeException exception) { available = false; }
    }

    @Override public Health health() {
        if (!properties.isEnabled()) return Health.up().withDetail("mode", "disabled").build();
        return (available ? Health.up() : Health.down())
                .withDetail("queueDepth", depth.get()).withDetail("expiredLeases", expired.get()).build();
    }
}
