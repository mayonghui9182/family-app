package com.familyapp.wechat.conversation;

import com.familyapp.wechat.conversation.WechatConversationAgentClient.RecentMessage;
import com.familyapp.wechat.conversation.WechatConversationSummaryClient.Request;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class WechatConversationSummaryClientTest {
    private static final String VALID = """
            {"confirmed_facts":["买菜"],"open_questions":[],"user_decisions":[],
             "candidate_overview":[],"last_message_id":101}
            """;

    private Request request() {
        return new Request(11L, null, List.of(new RecentMessage(101L,
                MessageDirection.INBOUND, "明天买菜", Instant.parse("2026-10-04T00:00:00Z"))));
    }

    @Test
    void sends_minimum_context_and_accepts_exact_cursor() {
        RestTemplate template = new RestTemplate();
        var server = MockRestServiceServer.bindTo(template).build();
        var client = new WechatConversationSummaryClient("http://agent.test", "secret", template);
        server.expect(requestTo("http://agent.test/api/internal/task-conversations/summarize"))
                .andExpect(header("X-Internal-Secret", "secret"))
                .andExpect(jsonPath("$.conversation_id").value(11))
                .andExpect(jsonPath("$.messages[0].message_id").value(101))
                .andExpect(jsonPath("$.openid").doesNotExist())
                .andExpect(jsonPath("$.internal_secret").doesNotExist())
                .andRespond(withSuccess(VALID, MediaType.APPLICATION_JSON));
        assertThat(client.summarize(request())).isPresent();
        server.verify();
    }

    @Test
    void rejects_privileged_fields_wrong_cursor_and_service_failure() {
        for (String response : List.of(VALID.replace("101", "102"),
                VALID.replace("\"confirmed_facts\"", "\"authorized\":true,\"confirmed_facts\""),
                VALID.replace("\"user_decisions\":[]", "\"user_decisions\":null"))) {
            RestTemplate template = new RestTemplate();
            var server = MockRestServiceServer.bindTo(template).build();
            var client = new WechatConversationSummaryClient("http://agent.test", "secret", template);
            server.expect(anything()).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
            assertThat(client.summarize(request())).isEmpty();
            server.verify();
        }
        RestTemplate template = new RestTemplate();
        var server = MockRestServiceServer.bindTo(template).build();
        var client = new WechatConversationSummaryClient("http://agent.test", "secret", template);
        server.expect(anything()).andRespond(withServerError());
        assertThat(client.summarize(request())).isEmpty();
        server.verify();
    }

    @Test
    void rejects_duplicate_message_ids_before_network() {
        RestTemplate template = new RestTemplate();
        var server = MockRestServiceServer.bindTo(template).build();
        var client = new WechatConversationSummaryClient("http://agent.test", "secret", template);
        assertThat(client.summarize(new Request(11L, null,
                List.of(request().messages().get(0), request().messages().get(0))))).isEmpty();
        assertThat(client.decode(VALID.replace("101", "\"101\""))).isEmpty();
        server.verify();
    }
}
