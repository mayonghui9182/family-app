package com.familyapp.wechat.conversation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class WechatConversationRetentionJobTest {
    @Test
    void defaultsDoNotRunRetentionAndLogsOnlyContainCountsOrStableReasons(CapturedOutput output) {
        var service = mock(WechatConversationRetentionService.class);
        var properties = new WechatConversationProperties();
        var now = Instant.parse("2026-10-04T00:00:00Z");
        var job = new WechatConversationRetentionJob(service, properties, Clock.fixed(now, ZoneOffset.UTC));
        job.poll();
        properties.setEnabled(true); properties.setWorkerEnabled(true);
        job.poll();
        verifyNoInteractions(service);
        properties.setRetentionEnabled(true);
        when(service.familyScopes(0)).thenReturn(List.of(1L, 2L));
        when(service.unroutedScopes(0)).thenReturn(List.of("private-account"));
        when(service.cleanFamily(1L, now)).thenReturn(new WechatConversationRetentionService.Counts(3, 2, 1));
        when(service.cleanFamily(2L, now)).thenThrow(new IllegalStateException("私人正文 private-openid"));
        when(service.cleanUnrouted("private-account", now)).thenReturn(1);
        job.poll();
        assertTrue(output.getOut().contains("messages=4 summaries=2 candidates=1"));
        assertTrue(output.getOut().contains("RETENTION_FAILED"));
        for (String secret : List.of("私人正文", "private-openid", "private-account"))
            assertFalse(output.getAll().contains(secret));
    }
}
