package com.familyapp.daily;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 隔离账号与服务桩验证机器入口，不连接实际工作源。 */
@SpringBootTest(properties={"daily.remote-enabled=true","spring.datasource.url=jdbc:h2:mem:dailyinternal;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
class DailyInternalApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @MockBean DailyService daily;

    @Test void scoped_read_rejects_missing_secret_and_deleted_account() throws Exception {
        String response=mvc.perform(post("/api/auth/register").contentType("application/json")
                .content("{\"email\":\"internal-daily@example.test\",\"password\":\"password123\",\"nickname\":\"验收\",\"createFamily\":false}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id=json.readTree(response).path("data").path("userId").asLong();
        String path="/api/internal/daily-read/"+id+"/plans/2026-10-10";
        when(daily.read(eq(id),eq(LocalDate.of(2026,10,10)),eq("PLAN")))
                .thenReturn(json.createObjectNode().put("missing",true));
        mvc.perform(get(path).header("X-Access-Token",json.readTree(response).path("data").path("accessToken").asText()))
                .andExpect(status().isUnauthorized());
        verify(daily,never()).read(anyLong(),any(),anyString());
        mvc.perform(get(path).header("X-Internal-Secret","test-internal-secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.missing").value(true));
        verify(daily).read(id,LocalDate.of(2026,10,10),"PLAN");
        db.update("UPDATE users SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",id);
        clearInvocations(daily);
        mvc.perform(get(path).header("X-Internal-Secret","test-internal-secret"))
                .andExpect(status().isUnauthorized());
        verify(daily,never()).read(anyLong(),any(),anyString());
    }
}
