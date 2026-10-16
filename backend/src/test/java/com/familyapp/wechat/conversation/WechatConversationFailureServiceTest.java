package com.familyapp.wechat.conversation;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.TransactionStatus;
import java.time.Instant;
import java.util.Optional;
import static org.mockito.Mockito.*;

import static org.assertj.core.api.Assertions.assertThat;

class WechatConversationFailureServiceTest {

    @Test
    void backoffDoublesFromInitialDelayAndStopsAtConfiguredMaximum() {
        WechatConversationProperties properties = new WechatConversationProperties();
        properties.setRetryInitialDelayMs(1_000);
        properties.setRetryMaxDelayMs(60_000);
        WechatConversationFailureService service =
                new WechatConversationFailureService(
                        null, null, properties, null, null);

        assertThat(service.backoffDelayMillis(1)).isEqualTo(1_000);
        assertThat(service.backoffDelayMillis(2)).isEqualTo(2_000);
        assertThat(service.backoffDelayMillis(3)).isEqualTo(4_000);
        assertThat(service.backoffDelayMillis(6)).isEqualTo(32_000);
        assertThat(service.backoffDelayMillis(7)).isEqualTo(60_000);
        assertThat(service.backoffDelayMillis(20)).isEqualTo(60_000);
    }

    private record Fixture(WechatConversationFailureService service,
                           WechatConversationMessageRepository messages,
                           WechatChatContextRepository contexts,
                           WechatConversationOutboxService outbox) {}

    private Fixture fixture(long currentFamily) {
        var messages = mock(WechatConversationMessageRepository.class);
        var contexts = mock(WechatChatContextRepository.class);
        var outbox = mock(WechatConversationOutboxService.class);
        var properties = new WechatConversationProperties();
        properties.setMaxAttempts(2);
        var transaction = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> callback) {
                return callback.doInTransaction(mock(TransactionStatus.class));
            }
        };
        var context = new WechatChatContext();
        context.setCurrentFamilyId(currentFamily);
        context.setUserId(7L);
        when(contexts.findByWechatBindIdForUpdate(3L)).thenReturn(Optional.of(context));
        when(contexts.releaseLease(eq(3L), eq("worker"), any())).thenReturn(1);
        var source = new WechatConversationMessage();
        org.springframework.test.util.ReflectionTestUtils.setField(source, "id", 11L);
        source.setWechatBindId(3L);
        source.setUserId(7L);
        source.setFamilyId(5L);
        source.setConversationId(9L);
        when(messages.findByIdAndWechatBindId(11L, 3L)).thenReturn(Optional.of(source));
        when(messages.findByIdAndFamilyId(11L, 5L)).thenReturn(Optional.of(source));
        when(messages.failProcessing(eq(11L), eq(3L), eq("worker"), anyString(), any(), anyInt()))
                .thenReturn(1);
        when(messages.retryProcessing(eq(11L), eq(3L), eq("worker"), anyString(), any(), any(), anyInt()))
                .thenReturn(1);
        return new Fixture(new WechatConversationFailureService(
                messages, contexts, properties, transaction, outbox), messages, contexts, outbox);
    }

    @Test
    void exhaustedFailureQueuesOneScopedReplyWithStableDedupeKey() {
        var f = fixture(5L);
        Instant now = Instant.parse("2026-10-15T09:00:00Z");
        var lease = new WechatConversationLeaseService.MessageLease(11, 3, "worker", now.plusSeconds(120), 2);
        assertThat(f.service().recordFailure(lease, "AI_TIMEOUT", now).outcome())
                .isEqualTo(WechatConversationFailureService.FailureOutcome.FAILED);
        verify(f.outbox()).enqueueReply(any(), eq(5L), eq(9L), eq("failure:11"),
                contains("暂未生成待办"), eq(now));
        verify(f.messages()).findByIdAndFamilyId(11L, 5L);
    }

    @Test
    void retryDoesNotNotifyAndFamilySwitchSuppressesOldFamilyNotice() {
        var f = fixture(5L);
        Instant now = Instant.parse("2026-10-15T09:00:00Z");
        var lease = new WechatConversationLeaseService.MessageLease(11, 3, "worker", now.plusSeconds(120), 1);
        assertThat(f.service().recordFailure(lease, "AI_TIMEOUT", now).outcome())
                .isEqualTo(WechatConversationFailureService.FailureOutcome.RETRY_SCHEDULED);
        verifyNoInteractions(f.outbox());
        var changed = fixture(6L);
        var lastLease = new WechatConversationLeaseService.MessageLease(11, 3, "worker", now.plusSeconds(120), 2);
        changed.service().recordFailure(lastLease, "AI_TIMEOUT", now);
        verifyNoInteractions(changed.outbox());
    }

    @Test
    void invalidResponseFailsImmediatelyInsteadOfRepeatingModelCall() {
        var f = fixture(5L);
        Instant now = Instant.parse("2026-10-15T09:00:00Z");
        var lease = new WechatConversationLeaseService.MessageLease(11, 3, "worker", now.plusSeconds(120), 1);
        assertThat(f.service().recordFailure(lease, "AI_INVALID_RESPONSE", now).outcome())
                .isEqualTo(WechatConversationFailureService.FailureOutcome.FAILED);
        verify(f.messages(), never()).retryProcessing(any(), any(), any(), any(), any(), any(), anyInt());
        verify(f.outbox()).enqueueReply(any(), eq(5L), eq(9L), eq("failure:11"), anyString(), eq(now));
    }
}

