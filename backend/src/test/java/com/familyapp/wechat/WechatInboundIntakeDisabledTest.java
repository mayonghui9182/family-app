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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 总开关关闭时的受理行为测试（Change 14 Task 2 用例⑧）。
 *
 * <p>开关取值与功能用例相反，必须独占一个测试类（{@code @TestPropertySource} 不可混用）。
 * 关开关是生产第一回退手段，因此这里锁死两件事：
 * ① 不落 {@code PENDING}（否则作业会照常跑 LLM，开关形同虚设）；
 * ② 短路发生在 {@code loginByOpenid} 之前（{@code user_id} 为空即证据，
 * 避免「开关关着却还在给用户补发 access token」这种隐性副作用）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "wechat.intake.enabled=false")
@Transactional
class WechatInboundIntakeDisabledTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("⑧ 开关关闭 → reason=intake_disabled，落 SKIPPED 留痕且绝不落 PENDING")
    void intakeDisabled_skippedWithoutPending() throws Exception {
        String openid = "o-intake-disabled-" + SEQ.incrementAndGet();
        String email = "disabled" + SEQ.incrementAndGet() + "@example.com";
        mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                    "email", email, "password", "password123", "nickname", "入站用户"))))
            .andExpect(jsonPath("$.code").value(0));
        String ticketBody = mvc.perform(post("/api/internal/wechat/ticket")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("openid", openid))))
            .andExpect(jsonPath("$.code").value(0))
            .andReturn().getResponse().getContentAsString();
        String ticket = MAPPER.readTree(ticketBody).get("data").get("ticket").asText();
        mvc.perform(post("/api/auth/wechat-bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                    "ticket", ticket, "email", email, "password", "password123"))))
            .andExpect(jsonPath("$.code").value(0));

        String msgId = "msg-disabled-" + SEQ.incrementAndGet();
        String body = mvc.perform(post("/api/internal/wechat/inbound-message")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                    "openid", openid, "msg_id", msgId, "content", "周六带爸去复查",
                    "received_at", "2026-09-13T08:00:00Z"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn().getResponse().getContentAsString();
        JsonNode data = MAPPER.readTree(body).get("data");

        assertEquals(false, data.get("accepted").asBoolean());
        assertEquals("intake_disabled", data.get("reason").asText());

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status, skip_reason, family_id, user_id FROM wechat_inbound_message WHERE msg_id = ?",
            msgId);
        assertEquals("SKIPPED", row.get("status"));
        assertEquals("intake_disabled", row.get("skip_reason"));
        assertNull(row.get("user_id"), "开关关闭时应在查绑定之前短路，不产生任何用户侧副作用");
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM wechat_inbound_message WHERE status = 'PENDING'",
            Integer.class).intValue());
    }
}
