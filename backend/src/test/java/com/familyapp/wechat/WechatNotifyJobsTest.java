package com.familyapp.wechat;

import com.familyapp.family.Family;
import com.familyapp.family.FamilyRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Change 12：08:00 早报 job 单元测试——逐家庭隔离，单家失败不阻断整批。
 */
class WechatNotifyJobsTest {

    private final FamilyRepository familyRepo = mock(FamilyRepository.class);
    private final WechatNotifyService service = mock(WechatNotifyService.class);
    private final WechatNotifyJobs jobs = new WechatNotifyJobs(familyRepo, service);

    private Family family(long id) {
        Family f = new Family();
        f.setId(id);
        return f;
    }

    @Test
    void dailyDigestJob_iterates_all_families_and_isolates_single_family_failure() {
        when(familyRepo.findAll()).thenReturn(List.of(family(1), family(2), family(3)));
        WechatNotifyService.DigestResult ok =
                new WechatNotifyService.DigestResult(2, 3, 1, true);
        when(service.digestForFamily(eq(1L), any(LocalDate.class))).thenReturn(ok);
        when(service.digestForFamily(eq(3L), any(LocalDate.class))).thenReturn(ok);
        doThrow(new RuntimeException("模拟单家庭推送异常"))
                .when(service).digestForFamily(eq(2L), any(LocalDate.class));

        jobs.dailyDigestJob();  // 不抛出即通过

        verify(service).digestForFamily(eq(1L), any(LocalDate.class));
        verify(service).digestForFamily(eq(2L), any(LocalDate.class));
        verify(service).digestForFamily(eq(3L), any(LocalDate.class));
    }
}
