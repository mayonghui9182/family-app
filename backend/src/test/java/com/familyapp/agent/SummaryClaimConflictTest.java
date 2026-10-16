package com.familyapp.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Change 5 Task 0：分布式锁冲突路径（H2 无表达式唯一索引，用 @MockBean 模拟
 * PG uk_sel_family_run_date UniqueViolation → acquired=false skip）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("internal-api-test")
@TestPropertySource(properties = "internal-api.secret=test-secret-correct")
class SummaryClaimConflictTest {

    @Autowired MockMvc mvc;
    @MockBean SummaryLockService summaryLockService;

    @Test
    void claim_uniqueViolation_returnsAcquiredFalse_skip() throws Exception {
        when(summaryLockService.claim(eq(9101L), any(), eq(false)))
                .thenThrow(new DataIntegrityViolationException("uk_sel_family_run_date"));
        when(summaryLockService.findSameDay(9101L)).thenReturn(null);

        mvc.perform(post("/api/internal/agent/summary-log/claim?family_id=9101")
                        .header("X-Internal-Secret", "test-secret-correct")
                        .contentType("application/json")
                        .content("{\"trigger_source\":\"auto\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.acquired").value(false));
    }

    @Test
    void claim_conflictWithExistingRow_returnsItsId() throws Exception {
        SummaryExecutionLog existing = new SummaryExecutionLog();
        existing.setId(777L);
        when(summaryLockService.claim(eq(9101L), any(), anyBoolean()))
                .thenThrow(new DataIntegrityViolationException("uk_sel_family_run_date"));
        when(summaryLockService.findSameDay(9101L)).thenReturn(existing);

        mvc.perform(post("/api/internal/agent/summary-log/claim?family_id=9101&force=false")
                        .header("X-Internal-Secret", "test-secret-correct")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(jsonPath("$.data.acquired").value(false))
                .andExpect(jsonPath("$.data.log_id").value(777));
    }
}
