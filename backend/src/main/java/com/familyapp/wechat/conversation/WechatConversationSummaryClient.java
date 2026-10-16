package com.familyapp.wechat.conversation;

import com.familyapp.wechat.conversation.WechatConversationAgentClient.RecentMessage;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.util.List;
import java.util.Optional;

/** 固定结构摘要契约；请求不含微信标识，响应没有任何业务写授权字段。 */
@Component
public class WechatConversationSummaryClient {
    private final String baseUrl;
    private final String secret;
    private final RestTemplate template;
    private final JsonMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    @Autowired
    public WechatConversationSummaryClient(@Value("${agent-service.base-url:http://localhost:8700}") String baseUrl,
            @Value("${internal-api.secret}") String secret) {
        this(baseUrl, secret, WechatConversationAgentClient.buildTemplate(3000, 45000));
    }

    WechatConversationSummaryClient(String baseUrl, String secret, RestTemplate template) {
        this.baseUrl = baseUrl; this.secret = secret; this.template = template;
    }

    public Optional<Summary> summarize(Request request) {
        try {
            if (request.conversationId() == null || request.conversationId() < 1
                    || request.messages() == null || request.messages().isEmpty() || request.messages().size() > 100)
                return Optional.empty();
            long prior = request.previousSummary() == null ? 0 : request.previousSummary().lastMessageId();
            for (var message : request.messages()) {
                if (message.messageId() <= prior || message.content() == null || message.content().isBlank()
                        || message.content().length() > 2000 || message.direction() == null || message.createdAt() == null)
                    return Optional.empty();
                prior = message.messageId();
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-Internal-Secret", secret);
            String response = template.postForObject(baseUrl + "/api/internal/task-conversations/summarize",
                    new HttpEntity<>(mapper.writeValueAsString(request), headers), String.class);
            var result = decode(response);
            return result.filter(value -> value.lastMessageId().equals(request.messages().get(request.messages().size()-1).messageId()));
        } catch (Exception exception) {
            // 不记录 HTTP 错误正文，失败保留上一版摘要和游标，后续轮询再尝试。
            return Optional.empty();
        }
    }

    public Optional<Summary> decode(String json) {
        try {
            if (json == null || json.length() > 4000) return Optional.empty();
            var summary = mapper.readValue(json, Summary.class);
            if (summary.lastMessageId() == null || summary.lastMessageId() < 1) return Optional.empty();
            for (var items : List.of(summary.confirmedFacts(), summary.openQuestions(),
                    summary.userDecisions(), summary.candidateOverview())) {
                if (items.size() > 20 || items.stream().anyMatch(item -> item == null || item.isBlank() || item.length() > 300))
                    return Optional.empty();
            }
            return Optional.of(summary);
        } catch (Exception exception) { return Optional.empty(); }
    }

    public String encode(Summary summary) {
        try {
            String json = mapper.writeValueAsString(summary);
            if (decode(json).isEmpty()) throw new IllegalArgumentException("摘要不符合固定结构");
            return json;
        } catch (java.io.IOException exception) { throw new IllegalArgumentException("摘要编码失败", exception); }
    }

    public record Request(@JsonProperty("conversation_id") Long conversationId,
            @JsonProperty("previous_summary") Summary previousSummary, List<RecentMessage> messages) { }
    public record Summary(@JsonProperty("confirmed_facts") List<String> confirmedFacts,
            @JsonProperty("open_questions") List<String> openQuestions,
            @JsonProperty("user_decisions") List<String> userDecisions,
            @JsonProperty("candidate_overview") List<String> candidateOverview,
            @JsonProperty("last_message_id") Long lastMessageId) { }
}
