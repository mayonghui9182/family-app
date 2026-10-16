package com.familyapp.wechat;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 受理端点的内部秘钥守护测试（Change 14 Task 2 用例⑦）。
 *
 * <p>必须独占 {@code internal-api-test} Profile：{@code InternalSecretInterceptor} 在
 * {@code test} / {@code internal-mock} Profile 下直接放行，只有本 Profile 才会真校验。
 *
 * <p>期望值为 HTTP 401 + 业务码 40101/40102（拦截器 {@code writeError} 的既有契约，
 * 与 {@code InternalSecretTest} 一致）；tasks.md 原文写的 403 是文档笔误，已按真实契约订正。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("internal-api-test")
@TestPropertySource(properties = "internal-api.secret=test-secret-correct")
@Transactional
class WechatInboundSecretGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired MockMvc mvc;

    private String inboundBody() throws Exception {
        return MAPPER.writeValueAsString(Map.of(
            "openid", "o-secret-guard", "msg_id", "msg-secret-guard",
            "content", "周六带爸去复查", "received_at", "2026-09-13T08:00:00Z"));
    }

    @Test
    @DisplayName("⑦a 缺失 X-Internal-Secret → 401 + 40101，且不落任何入站行")
    void missingSecret_returns401() throws Exception {
        mvc.perform(post("/api/internal/wechat/inbound-message")
                .contentType(MediaType.APPLICATION_JSON)
                .content(inboundBody()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("⑦b 错误 X-Internal-Secret → 401 + 40102")
    void wrongSecret_returns401() throws Exception {
        mvc.perform(post("/api/internal/wechat/inbound-message")
                .header("X-Internal-Secret", "test-secret-wrong")
                .contentType(MediaType.APPLICATION_JSON)
                .content(inboundBody()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(40102));
    }
}
