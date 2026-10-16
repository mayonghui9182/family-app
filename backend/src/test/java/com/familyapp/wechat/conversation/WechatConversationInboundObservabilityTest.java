package com.familyapp.wechat.conversation;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.familyapp.wechat.WechatBindRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.Optional;

import static com.familyapp.wechat.conversation.WechatConversationInboundDtos.MessageRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WechatConversationInboundObservabilityTest {

    @Test
    void metricsDistinguishPersistedDuplicateAndFailureWithoutLeakingUserText() {
        WechatConversationMessageRepository messageRepository =
                mock(WechatConversationMessageRepository.class);
        WechatBindRepository bindRepository = mock(WechatBindRepository.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);

        WechatConversationMessage existing = message("msg-duplicate");
        when(messageRepository.findByWechatAccountIdAndExternalMsgId(any(String.class), any(String.class)))
                .thenReturn(Optional.empty(), Optional.of(existing), Optional.empty());
        when(bindRepository.findByOpenid(any(String.class)))
                .thenReturn(Optional.empty(), Optional.empty());
        when(messageRepository.save(any(WechatConversationMessage.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0, WechatConversationMessage.class))
                .thenThrow(new IllegalStateException("数据库暂时不可用"));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WechatConversationInboundMetrics metrics =
                new WechatConversationInboundMetrics(registry);
        WechatConversationInboundService service =
                new WechatConversationInboundService(
                        messageRepository,
                        bindRepository,
                        transactionManager,
                        metrics,
                        mock(WechatUndeliveredResultHintService.class));

        String openid = "o-sensitive-openid-001";
        String content = "这是绝不能进入日志的家庭隐私正文";
        Logger logger = (Logger) LoggerFactory.getLogger(
                WechatConversationInboundService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.accept(request("msg-persisted", openid, content));
            service.accept(request("msg-duplicate", openid, content));
            assertThatThrownBy(() ->
                    service.accept(request("msg-failed", openid, content)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("数据库暂时不可用");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertOutcome(registry, WechatConversationInboundMetrics.OUTCOME_PERSISTED);
        assertOutcome(registry, WechatConversationInboundMetrics.OUTCOME_DUPLICATE);
        assertOutcome(registry, WechatConversationInboundMetrics.OUTCOME_FAILED);

        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(logs)
                .contains(WechatConversationInboundService.hashOpenid(openid))
                .doesNotContain(openid)
                .doesNotContain(content);
    }

    private void assertOutcome(SimpleMeterRegistry registry, String outcome) {
        assertThat(registry.get(WechatConversationInboundMetrics.TOTAL_NAME)
                .tag("outcome", outcome)
                .counter()
                .count()).isEqualTo(1);
        assertThat(registry.get(WechatConversationInboundMetrics.LATENCY_NAME)
                .tag("outcome", outcome)
                .timer()
                .count()).isEqualTo(1);
    }

    private MessageRequest request(String messageId, String openid, String content) {
        return new MessageRequest(
                "official-a",
                openid,
                messageId,
                content,
                Instant.parse("2026-10-02T00:00:00Z"));
    }

    private WechatConversationMessage message(String messageId) {
        WechatConversationMessage message = new WechatConversationMessage();
        message.setWechatAccountId("official-a");
        message.setExternalMsgId(messageId);
        message.setOpenid("o-sensitive-openid-001");
        message.setDirection(MessageDirection.INBOUND);
        message.setContent("首次正文");
        message.setReceivedAt(Instant.parse("2026-10-02T00:00:00Z"));
        return message;
    }
}
