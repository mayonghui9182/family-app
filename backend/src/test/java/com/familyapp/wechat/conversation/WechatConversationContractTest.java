package com.familyapp.wechat.conversation;

import com.familyapp.wechat.conversation.WechatConversationAgentClient.ActionType;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.FailureKind;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnCallResult;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnRequest;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Java 端读取与 agent-service 相同的 v1 持续会话契约样例。 */
class WechatConversationContractTest {

    private static final Path CONTRACT_PATH = Path.of(
            System.getProperty("user.dir"),
            "..",
            "contracts",
            "wechat-task-conversation",
            "v1.json").normalize();
    private static final String TURN_URL =
            "http://agent.test/api/internal/task-conversations/turn";

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    void shared_turn_sample_matches_backend_dtos_actions_and_limits() throws IOException {
        JsonNode contract = readContract();
        TurnRequest request = objectMapper.treeToValue(
                contract.path("turn_request"),
                TurnRequest.class);
        String response = objectMapper.writeValueAsString(contract.path("turn_response"));

        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        server.expect(requestTo(TURN_URL))
                .andExpect(jsonPath("$.conversation_id").value(201))
                .andExpect(jsonPath("$.route_revision").value(3))
                .andExpect(jsonPath("$.family.family_id").value(11))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        WechatConversationAgentClient client = new WechatConversationAgentClient(
                "http://agent.test",
                "test-internal-secret",
                template);

        TurnCallResult result = client.turn(request);

        List<String> fixtureActions = StreamSupport.stream(
                        contract.path("action_values").spliterator(),
                        false)
                .map(JsonNode::asText)
                .toList();
        List<String> backendActions = Arrays.stream(ActionType.values())
                .map(Enum::name)
                .toList();

        assertThat(result.accepted()).isTrue();
        assertThat(result.advanceRevision()).isTrue();
        assertThat(result.failureKind()).isEqualTo(FailureKind.NONE);
        assertThat(result.action().tasks()).hasSize(1);
        assertThat(backendActions).containsExactlyElementsOf(fixtureActions);
        assertThat(contract.path("limits").path("candidate_min").asInt())
                .isEqualTo(WechatConversationAgentClient.MIN_CANDIDATE_TASKS);
        assertThat(contract.path("limits").path("candidate_max").asInt())
                .isEqualTo(WechatConversationAgentClient.MAX_CANDIDATE_TASKS);
        assertThat(contract.path("limits").path("recent_messages_max").asInt())
                .isEqualTo(WechatConversationAgentClient.MAX_RECENT_MESSAGES);
        assertThat(contract.path("limits").path("experiences_max").asInt())
                .isEqualTo(WechatConversationAgentClient.MAX_EXPERIENCES);
        server.verify();
    }

    @Test
    void shared_sample_uses_snake_case_and_contains_no_secret_or_openid() throws IOException {
        String raw = Files.readString(CONTRACT_PATH);
        JsonNode turnRequest = readContract().path("turn_request");

        assertThat(raw).contains("\"conversation_id\"", "\"route_revision\"");
        assertThat(raw).doesNotContain("conversationId", "routeRevision");
        assertThat(turnRequest.has("openid")).isFalse();
        assertThat(turnRequest.has("internal_secret")).isFalse();
        assertThat(turnRequest.path("family").path("family_id").asLong()).isEqualTo(11L);
    }

    @Test
    void shared_summary_cursor_and_error_envelope_match_backend_expectations()
            throws IOException {
        JsonNode contract = readContract();
        JsonNode summaryRequest = contract.path("summary_request");
        JsonNode messages = summaryRequest.path("messages");
        long lastInputMessageId = messages.get(messages.size() - 1)
                .path("message_id")
                .asLong();
        JsonNode summaryResponse = contract.path("summary_response");
        JsonNode errorEnvelope = contract.path("error_envelope");

        Set<String> summaryFields = new HashSet<>();
        summaryResponse.fieldNames().forEachRemaining(summaryFields::add);
        Set<String> errorFields = new HashSet<>();
        errorEnvelope.fieldNames().forEachRemaining(errorFields::add);

        assertThat(summaryResponse.path("last_message_id").asLong())
                .isEqualTo(lastInputMessageId)
                .isEqualTo(102L);
        assertThat(summaryFields).containsExactlyInAnyOrder(
                "confirmed_facts",
                "open_questions",
                "user_decisions",
                "candidate_overview",
                "last_message_id");
        assertThat(errorFields).containsExactlyInAnyOrder("code", "msg");
        assertThat(errorEnvelope.path("code").asInt()).isEqualTo(56002);
        assertThat(errorEnvelope.path("msg").asText()).isNotBlank();
    }

    private JsonNode readContract() throws IOException {
        return objectMapper.readTree(CONTRACT_PATH.toFile());
    }
}
