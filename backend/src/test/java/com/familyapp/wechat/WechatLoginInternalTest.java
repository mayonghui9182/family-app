package com.familyapp.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 微信免密登录内部端点测试（Change 11 Task 1）
 *
 * <p>验证：已绑定 openid 经内部端点换取的 family-app token 与密码登录同权
 * （可直接访问 /api/tasks）；未绑定 openid 返回 40115 业务码。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "internal-api-test"})
@TestPropertySource(properties = "internal-api.secret=test-secret-correct")
@Transactional
class WechatLoginInternalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET = "test-secret-correct";
    private static final String OPENID = "o-login-openid-0001";

    @Autowired MockMvc mvc;

    private String mintTicket(String openid) throws Exception {
        String body = mvc.perform(post("/api/internal/wechat/ticket")
                .header("X-Internal-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("openid", openid))))
            .andExpect(jsonPath("$.code").value(0))
            .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body).get("data").get("ticket").asText();
    }

    private void registerAndBind(String email) throws Exception {
        mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                    "email", email, "password", "password123", "nickname", "微信用户"))))
            .andExpect(jsonPath("$.code").value(0));
        String ticket = mintTicket(OPENID);
        mvc.perform(post("/api/auth/wechat-bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                    "ticket", ticket, "email", email, "password", "password123"))))
            .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("1. 已绑定 openid 免密换 token，且该 token 同权访问 /api/tasks")
    void boundOpenid_login_tokenHasFullPower() throws Exception {
        registerAndBind("wxlogin1@example.com");

        String resp = mvc.perform(post("/api/internal/wechat/login")
                .header("X-Internal-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("openid", OPENID))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.accessToken").isString())
            .andReturn().getResponse().getContentAsString();
        JsonNode data = MAPPER.readTree(resp).get("data");
        String token = data.get("accessToken").asText();
        long familyId = data.get("families").get(0).get("id").asLong();

        // token 与密码登录同权：带 X-Access-Token + X-Family-Id 可访问 /api/tasks
        mvc.perform(get("/api/tasks")
                .header("X-Access-Token", token)
                .header("X-Family-Id", String.valueOf(familyId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("2. 未绑定 openid 免密登录返回 40115 业务码，不创建数据")
    void unboundOpenid_login_returnsNotBound() throws Exception {
        mvc.perform(post("/api/internal/wechat/login")
                .header("X-Internal-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("openid", "o-never-bound-login"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40115));
    }
}
