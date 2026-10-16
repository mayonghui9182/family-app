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
class WechatFamilyDeletionRecoveryJobTest {
    @Test
    void disabledByDefaultAndFailureDoesNotBlockNextFamilyOrLogPrivateContent(CapturedOutput output) {
        var service=mock(WechatFamilyDeletionService.class);
        var properties=new WechatConversationProperties();
        var now=Instant.parse("2026-10-04T00:00:00Z");
        var job=new WechatFamilyDeletionRecoveryJob(service,properties,Clock.fixed(now,ZoneOffset.UTC));
        job.poll(); verifyNoInteractions(service);
        properties.setEnabled(true); job.poll(); verifyNoInteractions(service);
        properties.setWorkerEnabled(true);
        when(service.deletedFamilies(0)).thenReturn(List.of(1L,2L));
        doThrow(new IllegalStateException("私人正文 private-openid")).when(service).repairDeletedFamily(1L,now);
        job.poll();
        verify(service).repairDeletedFamily(2L,now);
        assertTrue(output.getAll().contains("DELETION_RECOVERY_FAILED"));
        assertFalse(output.getAll().contains("私人正文"));
        assertFalse(output.getAll().contains("private-openid"));
    }
}
