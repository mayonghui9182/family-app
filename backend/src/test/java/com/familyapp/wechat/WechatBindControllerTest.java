package com.familyapp.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 微信绑定域端点测试（Change 11 Task 1）
 *
 * <p>覆盖：内部查绑定（命中/未绑定业务码）、中间票据签发、公开绑定页提交
 * （成功/密码错 40113/票据失效/幂等/单次有效）。
 * 无密钥 403 由 InternalSecretInterceptor 既有测试覆盖（见 InternalSecretTest）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "internal-api-test"})
@TestPropertySource(properties = "internal-api.secret=test-secret-correct")
@Transactional
class WechatBindControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET = "test-secret-correct";
    private static final String OPENID = "o-test-openid-0001";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private JsonNode registerUser(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                    "email", email, "password", password, "nickname", "测试用户"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body).get("data");
    }

    private String mintTicket(String openid) throws Exception {
        String body = mvc.perform(post("/api/internal/wechat/ticket")
                .header("X-Internal-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("openid", openid))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body).get("data").get("ticket").asText();
    }

    private long bindCount(String openid) {
        Long c = jdbc.queryForObject(
            "SELECT COUNT(*) FROM wechat_bind WHERE openid = ?", Long.class, openid);
        return c == null ? 0 : c;
    }

    @Test
    @DisplayName("1. 内部查询绑定：未绑定 openid 返回 WECHAT_NOT_BOUND(40115) 业务码，非 500")
    void getBind_unbound_returnsBusinessCode() throws Exception {
        mvc.perform(get("/api/internal/wechat/bind")
                .header("X-Internal-Secret", SECRET)
                .param("openid", "o-never-bound"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40115));
    }

    @Test
    @DisplayName("2. 内部签发中间票据：返回 ticket 字符串")
    void mintTicket_ok() throws Exception {
        String ticket = mintTicket(OPENID);
        assertEquals(32, ticket.length(), "票据应为 32 位 hex");
    }

    @Test
    @DisplayName("3. 公开绑定页提交有效票据 + 正确凭证：写绑定行并返回登录态")
    void publicBind_success_createsBindAndToken() throws Exception {
        registerUser("wxbind1@example.com", "password123");
        String ticket = mintTicket(OPENID);

        mvc.perform(post("/api/auth/wechat-bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                    "ticket", ticket, "email", "wxbind1@example.com", "password", "password123"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.accessToken").isString());
        assertEquals(1, bindCount(OPENID));
    }

    @Test
    @DisplayName("4. 密码错误：40113 中文码，不写绑定行，票据未消费（可重试成功）")
    void publicBind_wrongPassword_40113_noBindRow_ticketReusable() throws Exception {
        registerUser("wxbind2@example.com", "password123");
        String ticket = mintTicket(OPENID);

        mvc.perform(post("/api/auth/wechat-bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                    "ticket", ticket, "email", "wxbind2@example.com", "password", "wrong-pass"))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(40113));
        assertEquals(0, bindCount(OPENID));

        // 票据仍可重试成功
        mvc.perform(post("/api/auth/wechat-bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                    "ticket", ticket, "email", "wxbind2@example.com", "password", "password123"))))
            .andExpect(jsonPath("$.code").value(0));
        assertEquals(1, bindCount(OPENID));
    }

    @Test
    @DisplayName("5. 票据不存在/伪造：WECHAT_TICKET_INVALID(40116)，引导从菜单重进")
    void publicBind_bogusTicket_40116() throws Exception {
        registerUser("wxbind3@example.com", "password123");
        mvc.perform(post("/api/auth/wechat-bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                    "ticket", "deadbeefdeadbeefdeadbeefdeadbeef",
                    "email", "wxbind3@example.com", "password", "password123"))))
            .andExpect(jsonPath("$.code").value(40116));
        assertEquals(0, bindCount(OPENID));
    }

    @Test
    @DisplayName("6. 票据单次有效：重复使用第二次返回 40116")
    void publicBind_ticketSingleUse_secondCallRejected() throws Exception {
        registerUser("wxbind4@example.com", "password123");
        String ticket = mintTicket(OPENID);
        String payload = MAPPER.writeValueAsString(Map.of(
            "ticket", ticket, "email", "wxbind4@example.com", "password", "password123"));

        mvc.perform(post("/api/auth/wechat-bind").contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/api/auth/wechat-bind").contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(jsonPath("$.code").value(40116));
    }

    @Test
    @DisplayName("7. 同一 openid 重复绑定幂等：再次走完流程返回成功且不产生重复行")
    void repeatBind_idempotent() throws Exception {
        registerUser("wxbind5@example.com", "password123");
        bindViaFlow("wxbind5@example.com", "password123", OPENID);
        assertEquals(1, bindCount(OPENID));

        // 另一账号重复绑同一 openid？规格限定一个 openid 最多绑定一个 user——幂等返回原绑定
        bindViaFlow("wxbind5@example.com", "password123", OPENID);
        assertEquals(1, bindCount(OPENID));
    }

    @Test
    @DisplayName("8. 内部查询绑定：已绑定 openid 返回 userId/nickname")
    void getBind_bound_returnsUserId() throws Exception {
        JsonNode user = registerUser("wxbind6@example.com", "password123");
        long userId = user.get("userId").asLong();
        bindViaFlow("wxbind6@example.com", "password123", OPENID);

        mvc.perform(get("/api/internal/wechat/bind")
                .header("X-Internal-Secret", SECRET)
                .param("openid", OPENID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.bound").value(true))
            .andExpect(jsonPath("$.data.userId").value(userId));
    }

    private void bindViaFlow(String email, String password, String openid) throws Exception {
        String ticket = mintTicket(openid);
        mvc.perform(post("/api/auth/wechat-bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                    "ticket", ticket, "email", email, "password", password))))
            .andExpect(jsonPath("$.code").value(0));
    }
}
