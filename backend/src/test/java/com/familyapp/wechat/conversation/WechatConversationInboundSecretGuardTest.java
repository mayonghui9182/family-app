package com.familyapp.wechat.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("internal-api-test")
@TestPropertySource(properties = "internal-api.secret=test-secret-correct")
@Transactional
class WechatConversationInboundSecretGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PATH = "/api/internal/wechat/conversation/messages";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void missingInternalSecretIsRejectedWithoutPersistence() throws Exception {
        mvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        assertNoMessage();
    }

    @Test
    void wrongInternalSecretIsRejectedWithoutPersistence() throws Exception {
        mvc.perform(post(PATH)
                        .header("X-Internal-Secret", "wrong-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40102));

        assertNoMessage();
    }

    private String requestBody() throws Exception {
        return MAPPER.writeValueAsString(Map.of(
                "wechat_account_id", "official-secret",
                "openid", "openid-secret",
                "msg_id", "message-secret",
                "content", "内部接口测试",
                "received_at", "2026-10-02T00:00:00Z"));
    }

    private void assertNoMessage() {
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM wechat_conversation_messages
                 WHERE wechat_account_id = 'official-secret'
                """, Integer.class));
    }
}
