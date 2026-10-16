package com.familyapp.wechat.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WechatConversationInboundControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PATH = "/api/internal/wechat/conversation/messages";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void firstBoundMessagePersistsCompleteEnvelopeWithinWechatDeadline() throws Exception {
        String openid = "conversation-bound-" + SEQUENCE.incrementAndGet();
        long userId = registerAndBind(openid);
        String content = "请帮我安排周末采购。" + "补充说明".repeat(120);
        String messageId = "conversation-msg-" + SEQUENCE.incrementAndGet();

        long startedAt = System.nanoTime();
        JsonNode data = submit("official-a", openid, messageId, content);
        long elapsed = System.nanoTime() - startedAt;

        assertTrue(elapsed < Duration.ofMillis(2500).toNanos(),
                "受理端点必须在微信 2.5 秒调用预算内返回");
        assertTrue(data.get("persisted").asBoolean());
        assertEquals(false, data.get("duplicate").asBoolean());
        assertEquals("RECEIVED", data.get("reply_type").asText());
        assertTrue(data.get("message_id").asLong() > 0);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT wechat_account_id, external_msg_id, openid, direction, content,
                       wechat_bind_id, user_id, family_id, conversation_id,
                       processing_status, attempts
                  FROM wechat_conversation_messages
                 WHERE id = ?
                """, data.get("message_id").asLong());
        assertEquals("official-a", row.get("wechat_account_id"));
        assertEquals(messageId, row.get("external_msg_id"));
        assertEquals(openid, row.get("openid"));
        assertEquals("INBOUND", row.get("direction"));
        assertEquals(content, row.get("content"), "完整正文不得沿用旧 intake 的 200 字符截断");
        assertNotNull(row.get("wechat_bind_id"));
        assertEquals(userId, ((Number) row.get("user_id")).longValue());
        assertNull(row.get("family_id"), "家庭路由属于后续任务，入站阶段不得猜家庭");
        assertNull(row.get("conversation_id"));
        assertEquals("PENDING", row.get("processing_status"));
        assertEquals(0, ((Number) row.get("attempts")).intValue());

        Instant receivedAt = jdbc.queryForObject(
                "SELECT received_at FROM wechat_conversation_messages WHERE id = ?",
                Instant.class,
                data.get("message_id").asLong());
        assertEquals(Instant.parse("2026-10-02T00:00:00Z"), receivedAt);
    }

    @Test
    void duplicateWithinSameOfficialAccountReturnsOriginalMessageWithoutOverwrite() throws Exception {
        String openid = "conversation-duplicate-" + SEQUENCE.incrementAndGet();
        registerAndBind(openid);
        String messageId = "conversation-shared-" + SEQUENCE.incrementAndGet();

        JsonNode first = submit("official-a", openid, messageId, "首次完整正文");
        JsonNode duplicate = submit("official-a", openid, messageId, "重推时的不同正文");

        assertEquals(false, first.get("duplicate").asBoolean());
        assertEquals(true, duplicate.get("duplicate").asBoolean());
        assertTrue(duplicate.get("persisted").asBoolean());
        assertEquals(first.get("message_id").asLong(), duplicate.get("message_id").asLong());
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM wechat_conversation_messages
                 WHERE wechat_account_id = ? AND external_msg_id = ?
                """, Integer.class, "official-a", messageId));
        assertEquals("首次完整正文", jdbc.queryForObject("""
                SELECT content FROM wechat_conversation_messages
                 WHERE wechat_account_id = ? AND external_msg_id = ?
                """, String.class, "official-a", messageId));
    }

    @Test
    void sameMessageIdFromDifferentOfficialAccountsPersistsSeparately() throws Exception {
        String openid = "conversation-multi-account-" + SEQUENCE.incrementAndGet();
        String messageId = "conversation-cross-account-" + SEQUENCE.incrementAndGet();

        JsonNode first = submit("official-a", openid, messageId, "公众号 A 消息");
        JsonNode second = submit("official-b", openid, messageId, "公众号 B 消息");

        assertEquals(false, first.get("duplicate").asBoolean());
        assertEquals(false, second.get("duplicate").asBoolean());
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM wechat_conversation_messages
                 WHERE external_msg_id = ?
                """, Integer.class, messageId));
    }

    @Test
    void unboundOpenidStillPersistsAuditMessage() throws Exception {
        String openid = "conversation-unbound-" + SEQUENCE.incrementAndGet();
        String messageId = "conversation-unbound-msg-" + SEQUENCE.incrementAndGet();

        JsonNode data = submit("official-a", openid, messageId, "我还没有绑定账号");

        assertTrue(data.get("persisted").asBoolean());
        assertEquals(false, data.get("duplicate").asBoolean());
        assertEquals("BIND_REQUIRED", data.get("reply_type").asText());

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT openid, wechat_bind_id, user_id, family_id, content
                  FROM wechat_conversation_messages
                 WHERE wechat_account_id = ? AND external_msg_id = ?
                """, "official-a", messageId);
        assertEquals(openid, row.get("openid"));
        assertNull(row.get("wechat_bind_id"));
        assertNull(row.get("user_id"));
        assertNull(row.get("family_id"));
        assertEquals("我还没有绑定账号", row.get("content"));
    }

    @Test
    void invalidEnvelopeIsRejectedBeforePersistence() throws Exception {
        String openid = "conversation-invalid-" + SEQUENCE.incrementAndGet();
        mvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "wechat_account_id", " ",
                                "openid", openid,
                                "msg_id", "invalid-msg",
                                "content", "测试",
                                "received_at", "2026-10-02T00:00:00Z"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40099));

        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM wechat_conversation_messages WHERE openid = ?
                """, Integer.class, openid));
    }

    private JsonNode submit(String accountId, String openid, String messageId, String content)
            throws Exception {
        String response = mvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "wechat_account_id", accountId,
                                "openid", openid,
                                "msg_id", messageId,
                                "content", content,
                                "received_at", "2026-10-02T00:00:00Z"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return MAPPER.readTree(response).get("data");
    }

    private long registerAndBind(String openid) throws Exception {
        int sequence = SEQUENCE.incrementAndGet();
        String email = "conversation" + sequence + "@example.com";
        String registerResponse = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "email", email,
                                "password", "password123",
                                "nickname", "会话用户"))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long userId = MAPPER.readTree(registerResponse).get("data").get("userId").asLong();

        String ticketResponse = mvc.perform(post("/api/internal/wechat/ticket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("openid", openid))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String ticket = MAPPER.readTree(ticketResponse).get("data").get("ticket").asText();

        mvc.perform(post("/api/auth/wechat-bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "ticket", ticket,
                                "email", email,
                                "password", "password123"))))
                .andExpect(jsonPath("$.code").value(0));
        return userId;
    }
}
