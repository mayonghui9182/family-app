package com.familyapp.wechat.conversation;

import com.familyapp.family.FamilyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 监控标签只有有限枚举；提交事件与回滚中的安全拒绝分别验证。 */
class WechatConversationOperationalMetricsTest {
    @Test
    void eventsAndErrorCodesRemainBoundedAndQueueHealthIsScoped() {
        var registry = new SimpleMeterRegistry();
        var jdbc = mock(JdbcTemplate.class);
        var families = mock(FamilyRepository.class);
        var properties = new WechatConversationProperties();
        var metrics = new WechatConversationOperationalMetrics(registry, jdbc, families, properties, Clock.systemUTC());
        assertEquals("UP", metrics.health().getStatus().getCode());
        metrics.refresh();
        verifyNoInteractions(jdbc);
        for (var event : WechatConversationOperationalMetrics.Event.values()) metrics.event(event);
        for (Integer code : new Integer[]{0, 40001, 40014, 42001, 45015, 43004, 999999, null}) metrics.outbox(code);
        for (var meter : registry.getMeters()) {
            for (var tag : meter.getId().getTags()) {
                assertTrue(List.of("event", "code").contains(tag.getKey()));
                assertFalse(tag.getValue().contains("openid"));
            }
        }
        properties.setEnabled(true);
        when(families.findAllActiveIds()).thenReturn(List.of(1L));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(1L))).thenReturn(4L);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(1L), any(java.sql.Timestamp.class))).thenReturn(2L);
        metrics.refresh();
        assertEquals(4.0, registry.get("familyapp.wechat.conversation.queue.depth").gauge().value());
        assertEquals(2.0, registry.get("familyapp.wechat.conversation.lease.expired").gauge().value());
        when(families.findAllActiveIds()).thenThrow(new IllegalStateException("敏感数据库详情"));
        metrics.refresh();
        assertEquals("DOWN", metrics.health().getStatus().getCode());
        assertFalse(metrics.health().getDetails().toString().contains("敏感"));
    }

    @Test
    void rolledBackBusinessEventsDoNotCountButSecurityDenialsDo() {
        var registry = new SimpleMeterRegistry();
        var metrics = new WechatConversationOperationalMetrics(registry, mock(JdbcTemplate.class),
                mock(FamilyRepository.class), new WechatConversationProperties(), Clock.systemUTC());
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            metrics.event(WechatConversationOperationalMetrics.Event.EXPERIENCE_ACTIVATED);
            metrics.event(WechatConversationOperationalMetrics.Event.CROSS_FAMILY_DENIED);
            assertNull(registry.find("familyapp.wechat.conversation.operation.total").tag("event", "experience_activated").counter());
            assertEquals(1.0, registry.get("familyapp.wechat.conversation.operation.total").tag("event", "cross_family_denied").counter().count());
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            assertEquals(1.0, registry.get("familyapp.wechat.conversation.operation.total").tag("event", "experience_activated").counter().count());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
