package com.familyapp.wechat.conversation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 微信会话入站低基数指标。
 *
 * <p>指标标签只包含固定结果枚举，禁止加入正文、openid 或消息 ID。</p>
 */
@Component
public class WechatConversationInboundMetrics {

    static final String TOTAL_NAME = "familyapp.wechat.conversation.inbound.total";
    static final String LATENCY_NAME = "familyapp.wechat.conversation.inbound.persistence";
    static final String OUTCOME_PERSISTED = "persisted";
    static final String OUTCOME_DUPLICATE = "duplicate";
    static final String OUTCOME_FAILED = "failed";

    private final MeterRegistry registry;

    public WechatConversationInboundMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(boolean duplicate, long elapsedNanos) {
        recordOutcome(
                duplicate ? OUTCOME_DUPLICATE : OUTCOME_PERSISTED,
                elapsedNanos);
    }

    public void recordFailure(long elapsedNanos) {
        recordOutcome(OUTCOME_FAILED, elapsedNanos);
    }

    private void recordOutcome(String outcome, long elapsedNanos) {
        Counter.builder(TOTAL_NAME)
                .description("微信公众号会话入站受理结果")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        Timer.builder(LATENCY_NAME)
                .description("微信公众号会话入站持久化耗时")
                .tag("outcome", outcome)
                .register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }
}
