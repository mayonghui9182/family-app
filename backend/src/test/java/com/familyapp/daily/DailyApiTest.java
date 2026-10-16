package com.familyapp.daily;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import java.time.*;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真正的登录拦截器与个人接口共同验收，无家庭账号也能访问。 */
@SpringBootTest(properties={"daily.enabled=true","spring.datasource.url=jdbc:h2:mem:dailyapitest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
class DailyApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockBean DailySources sources;
    @MockBean Clock clock;

    @BeforeEach void setup() {
        jdbc.execute("DROP TABLE IF EXISTS daily_records");jdbc.execute("DROP TABLE IF EXISTS daily_settings");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V17__Personal_Daily_Planning.sql")).execute(jdbc.getDataSource());
        Instant instant=Instant.parse("2026-10-10T00:30:00Z");
        when(clock.instant()).thenReturn(instant);when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.withZone(any())).thenAnswer(call->Clock.fixed(instant,call.getArgument(0)));
        when(sources.collect(anyLong(),any())).thenReturn(json.createArrayNode());
    }

    JsonNode register() throws Exception {
        String body="{\"email\":\"daily-"+UUID.randomUUID()+"@example.test\",\"nickname\":\"每日验收\",\"password\":\"password123\",\"createFamily\":false}";
        String result=mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).get("data");
    }

    @Test void no_family_user_has_private_records_and_stale_confirmation_conflicts() throws Exception {
        var first=register();var second=register();String token=first.path("accessToken").asText();
        mvc.perform(get("/api/daily/settings")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/daily/plans/generate").header("X-Access-Token",token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(0));
        mvc.perform(get("/api/daily/plans/2026-10-10").header("X-Access-Token",second.path("accessToken").asText())
                .param("userId",first.path("userId").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.missing").value(true));
        for(int n=0;n<2;n++)mvc.perform(post("/api/daily/plans/2026-10-10/confirm")
                .header("X-Access-Token",token).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(n==0?status().isOk():status().isConflict());
    }

    @Test void internal_identity_is_minimal_and_deleted_user_cannot_authenticate() throws Exception {
        var account=register();long id=account.path("userId").asLong();
        mvc.perform(get("/api/internal/work-users/"+id)).andExpect(status().isUnauthorized());
        String result=mvc.perform(get("/api/internal/work-users/"+id).header("X-Internal-Secret","test-internal-secret"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(1,json.readTree(result).path("data").size());
        jdbc.update("UPDATE users SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",id);
        mvc.perform(get("/api/auth/me").header("X-Access-Token",account.path("accessToken").asText()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/work-users/"+id).header("X-Internal-Secret","test-internal-secret"))
                .andExpect(status().isUnauthorized());
    }
}
