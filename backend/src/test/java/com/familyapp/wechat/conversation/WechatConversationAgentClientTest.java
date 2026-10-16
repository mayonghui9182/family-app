package com.familyapp.wechat.conversation;

import com.familyapp.family.FamilyRole;
import com.familyapp.task.TaskPriority;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.ActionType;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.CandidateSnapshot;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.CandidateTask;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.CategoryReference;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.ExperienceContext;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.FailureKind;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.FamilyContext;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.MemberReference;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.RecentMessage;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.SlotSource;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.SlotValue;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnCallResult;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** backend 到持续会话 Agent 的双重 schema 与安全降级测试。 */
class WechatConversationAgentClientTest {

    private static final String TURN_URL =
            "http://agent.test/api/internal/task-conversations/turn";

    private static final String PRESENT_RESPONSE = """
            {
              "action": "PRESENT_CANDIDATE",
              "reply": "我按您刚说的整理好了，请确认。",
              "tasks": [{
                "title": "周五买菜",
                "description": "按本轮要求由爸爸负责",
                "due_date": "2026-10-09",
                "priority": "MEDIUM",
                "category_id": 10,
                "assignee_profile_id": 21
              }],
              "slot_patch": {
                "assignee": {
                  "value": "爸爸",
                  "source": "USER_EXPLICIT",
                  "source_message_id": 101,
                  "experience_id": null
                }
              }
            }
            """;

    private ClientFixture fixture() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        WechatConversationAgentClient client =
                new WechatConversationAgentClient(
                        "http://agent.test", "test-internal-secret", template);
        return new ClientFixture(client, server);
    }

    @Test
    void turn_accepts_a_valid_category_name_proposal_over_http() {
        ClientFixture fixture = fixture();
        String response = PRESENT_RESPONSE.replace("\"category_id\": 10", "\"category_name\": \"家庭日常事务\"");
        fixture.server().expect(requestTo(TURN_URL)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        var result = fixture.client().turn(validRequest());
        assertThat(result.accepted()).isTrue();
        assertThat(result.action().tasks().get(0).categoryName()).isEqualTo("家庭日常事务");
        assertThat(result.action().tasks().get(0).categoryId()).isNull();
        fixture.server().verify();
    }

    @Test
    void turn_rejects_category_id_and_new_name_together() {
        ClientFixture fixture = fixture();
        String response = PRESENT_RESPONSE.replace("\"category_id\": 10", "\"category_id\": 10, \"category_name\": \"新分类\"");
        fixture.server().expect(requestTo(TURN_URL)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        var result = fixture.client().turn(validRequest());
        assertThat(result.accepted()).isFalse();
        assertThat(result.failureKind()).isEqualTo(FailureKind.INVALID_RESPONSE);
        fixture.server().verify();
    }

    @Test
    void turn_sends_only_current_family_minimum_context_and_accepts_valid_candidate() {
        ClientFixture fixture = fixture();
        fixture.server().expect(requestTo(TURN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Secret", "test-internal-secret"))
                .andExpect(jsonPath("$.family.family_id").value(11))
                .andExpect(jsonPath("$.family.family_name").value("一一家"))
                .andExpect(jsonPath("$.recent_messages.length()").value(2))
                .andExpect(jsonPath("$.experiences.length()").value(1))
                .andExpect(jsonPath("$.current_candidate.revision").value(2))
                .andExpect(jsonPath("$.openid").doesNotExist())
                .andExpect(jsonPath("$.internal_secret").doesNotExist())
                .andExpect(content().string(not(containsString("test-internal-secret"))))
                .andExpect(content().string(not(containsString("二二家"))))
                .andExpect(content().string(not(containsString("\"family_id\":12"))))
                .andRespond(withSuccess(PRESENT_RESPONSE, MediaType.APPLICATION_JSON));

        TurnCallResult result = fixture.client().turn(validRequest());

        assertThat(result.accepted()).isTrue();
        assertThat(result.advanceRevision()).isTrue();
        assertThat(result.failureKind()).isEqualTo(FailureKind.NONE);
        assertThat(result.action().action()).isEqualTo(ActionType.PRESENT_CANDIDATE);
        assertThat(result.action().tasks()).singleElement()
                .extracting(CandidateTask::assigneeProfileId)
                .isEqualTo(21L);
        fixture.server().verify();
    }

    @Test
    void turn_rejects_oversized_context_before_network_call() {
        ClientFixture fixture = fixture();
        List<RecentMessage> messages = new ArrayList<>();
        for (long id = 1; id <= 17; id++) {
            messages.add(new RecentMessage(
                    id,
                    MessageDirection.INBOUND,
                    "消息" + id,
                    Instant.parse("2026-10-01T00:00:00Z").plusSeconds(id)));
        }
        TurnRequest invalid = copyWith(messages, validRequest().experiences());

        TurnCallResult result = fixture.client().turn(invalid);

        assertThat(result.accepted()).isFalse();
        assertThat(result.advanceRevision()).isFalse();
        assertThat(result.action()).isNull();
        assertThat(result.failureKind()).isEqualTo(FailureKind.INVALID_REQUEST);
        assertThat(result.reply()).contains("稍后再发一次");
        fixture.server().verify();
    }

    @Test
    void turn_rejects_more_than_five_experiences_before_network_call() {
        ClientFixture fixture = fixture();
        List<ExperienceContext> experiences = new ArrayList<>();
        for (int id = 1; id <= 6; id++) {
            experiences.add(new ExperienceContext(
                    (long) id, 1, "preference:" + id, "偏好" + id));
        }
        TurnRequest invalid = copyWith(validRequest().recentMessages(), experiences);

        TurnCallResult result = fixture.client().turn(invalid);

        assertThat(result.accepted()).isFalse();
        assertThat(result.advanceRevision()).isFalse();
        assertThat(result.failureKind()).isEqualTo(FailureKind.INVALID_REQUEST);
        fixture.server().verify();
    }

    @Test
    void turn_rejects_unknown_privileged_response_field_without_advancing_revision() {
        ClientFixture fixture = fixture();
        String privileged = PRESENT_RESPONSE.replace(
                "\"slot_patch\": {",
                "\"tool_call\": {\"name\":\"create_task\"}, \"slot_patch\": {");
        fixture.server().expect(requestTo(TURN_URL))
                .andRespond(withSuccess(privileged, MediaType.APPLICATION_JSON));

        TurnCallResult result = fixture.client().turn(validRequest());

        assertThat(result.accepted()).isFalse();
        assertThat(result.advanceRevision()).isFalse();
        assertThat(result.action()).isNull();
        assertThat(result.failureKind()).isEqualTo(FailureKind.INVALID_RESPONSE);
        fixture.server().verify();
    }

    @Test
    void turn_rejects_candidate_reference_outside_current_family() {
        ClientFixture fixture = fixture();
        String crossFamily = PRESENT_RESPONSE.replace(
                "\"category_id\": 10", "\"category_id\": 999");
        fixture.server().expect(requestTo(TURN_URL))
                .andRespond(withSuccess(crossFamily, MediaType.APPLICATION_JSON));

        TurnCallResult result = fixture.client().turn(validRequest());

        assertThat(result.accepted()).isFalse();
        assertThat(result.advanceRevision()).isFalse();
        assertThat(result.failureKind()).isEqualTo(FailureKind.INVALID_RESPONSE);
        fixture.server().verify();
    }

    @Test
    void turn_timeout_returns_safe_fallback_without_advancing_revision() {
        ClientFixture fixture = fixture();
        fixture.server().expect(requestTo(TURN_URL))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        TurnCallResult result = fixture.client().turn(validRequest());

        assertThat(result.accepted()).isFalse();
        assertThat(result.advanceRevision()).isFalse();
        assertThat(result.action()).isNull();
        assertThat(result.failureKind()).isEqualTo(FailureKind.TIMEOUT);
        assertThat(result.reply()).contains("稍后再发一次");
        fixture.server().verify();
    }

    @Test
    void clarification_is_valid_but_does_not_advance_candidate_revision() {
        ClientFixture fixture = fixture();
        String response = """
                {
                  "action": "ASK_CLARIFICATION",
                  "reply": "请问由谁来负责呢？",
                  "slot_patch": {}
                }
                """;
        fixture.server().expect(requestTo(TURN_URL))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        TurnCallResult result = fixture.client().turn(validRequest());

        assertThat(result.accepted()).isTrue();
        assertThat(result.advanceRevision()).isFalse();
        assertThat(result.action().tasks()).isEmpty();
        assertThat(result.action().action()).isEqualTo(ActionType.ASK_CLARIFICATION);
        fixture.server().verify();
    }

    private TurnRequest validRequest() {
        return new TurnRequest(
                201L,
                3L,
                2,
                101L,
                "这次请爸爸周五买菜",
                new FamilyContext(11L, "一一家", 81L, FamilyRole.ADULT),
                List.of("topic", "assignee", "due_date"),
                Map.of("topic", new SlotValue(
                        "买菜", SlotSource.USER_EXPLICIT, 99L, null)),
                new CandidateSnapshot(2, List.of(new CandidateTask(
                        "买菜",
                        "",
                        null,
                        TaskPriority.MEDIUM,
                        10L,
                        20L))),
                "用户要安排本周买菜。",
                List.of(
                        new RecentMessage(
                                99L,
                                MessageDirection.INBOUND,
                                "安排买菜",
                                Instant.parse("2026-10-01T00:00:00Z")),
                        new RecentMessage(
                                100L,
                                MessageDirection.OUTBOUND,
                                "请问由谁负责？",
                                Instant.parse("2026-10-01T00:00:01Z"))),
                List.of(new ExperienceContext(
                        30L, 2, "assignee:shopping", "买菜通常由妈妈负责")),
                List.of(new CategoryReference(10L, "家务")),
                List.of(
                        new MemberReference(20L, "妈妈", 82L),
                        new MemberReference(21L, "爸爸", 83L)));
    }

    private TurnRequest copyWith(
            List<RecentMessage> messages,
            List<ExperienceContext> experiences) {
        TurnRequest source = validRequest();
        return new TurnRequest(
                source.conversationId(),
                source.routeRevision(),
                source.currentRevision(),
                source.messageId(),
                source.userText(),
                source.family(),
                source.allowedSlotKeys(),
                source.slotState(),
                source.currentCandidate(),
                source.rollingSummary(),
                messages,
                experiences,
                source.categories(),
                source.members());
    }

    private record ClientFixture(
            WechatConversationAgentClient client,
            MockRestServiceServer server) {
    }

    @Test
    void upstreamBusinessErrorsKeepTimeoutAndInvalidResponseClassification() {
        for (int code : new int[]{56001, 56002}) {
            var f = fixture();
            f.server().expect(requestTo(TURN_URL)).andRespond(
                    org.springframework.test.web.client.response.MockRestResponseCreators
                            .withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY)
                            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":" + code + "}"));
            assertThat(f.client().turn(validRequest()).failureKind())
                    .isEqualTo(code == 56001 ? FailureKind.TIMEOUT : FailureKind.INVALID_RESPONSE);
            f.server().verify();
        }
    }
}
