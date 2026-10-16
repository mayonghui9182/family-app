package com.familyapp.agent;

import com.familyapp.agent.AgentCallbackClient.DraftSubtask;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Change 14 Task 6：backend → agent-service A-2 草稿模式（/api/v1/chat + session_id）客户端。
 * 铁律：① 走用户态鉴权（X-Family-Id + X-Access-Token），绝不带内网密钥；
 * ② code!=0、subtasks 为空、传输/解析失败一律抛出，交作业侧按 D8 重试语义处理。
 */
class AgentCallbackClientTest {

    private static final String CHAT_URL = "http://agent.test/api/v1/chat";

    private AgentCallbackClient client(RestTemplate rt) {
        return new AgentCallbackClient("http://agent.test", "test-internal-secret", rt);
    }

    private static final String OK_TWO_SUBTASKS = "{\"code\":0,\"msg\":\"操作成功\",\"data\":{"
            + "\"task_id\":null,\"title\":\"给孙子办生日\","
            + "\"subtasks\":[{\"title\":\"订蛋糕\",\"description\":\"周五前送到\","
            + "\"parent_task_uuid\":null,\"estimated_hours\":null,\"category_id\":null,"
            + "\"notes\":[],\"sort_order\":1},"
            + "{\"title\":\"买气球\",\"description\":null,\"parent_task_uuid\":null,"
            + "\"estimated_hours\":null,\"category_id\":null,\"notes\":[],\"sort_order\":2}]}}";

    /** ① 请求形状：URL、用户态头、body 含 message + session_id，且不得带内网密钥。 */
    @Test
    void decomposeDraft_posts_to_v1_chat_with_user_scoped_headers() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Family-Id", "7"))
                .andExpect(header("X-Access-Token", "access-token-1"))
                .andExpect(headerDoesNotExist("X-Internal-Secret"))
                .andExpect(jsonPath("$.message").value("给孙子办生日"))
                .andExpect(jsonPath("$.session_id").value(42))
                .andRespond(withSuccess(OK_TWO_SUBTASKS, MediaType.APPLICATION_JSON));

        List<DraftSubtask> subtasks = client(rt).decomposeDraft(7L, "access-token-1", "给孙子办生日", 42L);

        assertThat(subtasks).hasSize(2);
        server.verify();
    }

    /** ② code==0 → 返回 subtasks 列表（草稿模式只认 title/description/sort_order 三字段）。 */
    @Test
    void decomposeDraft_maps_subtask_fields() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo(CHAT_URL))
                .andRespond(withSuccess(OK_TWO_SUBTASKS, MediaType.APPLICATION_JSON));

        List<DraftSubtask> subtasks = client(rt).decomposeDraft(7L, "t", "给孙子办生日", 42L);

        assertThat(subtasks).extracting(DraftSubtask::title).containsExactly("订蛋糕", "买气球");
        assertThat(subtasks.get(0).description()).isEqualTo("周五前送到");
        assertThat(subtasks.get(0).sortOrder()).isEqualTo(1);
        assertThat(subtasks.get(1).description()).isNull();
        assertThat(subtasks.get(1).sortOrder()).isEqualTo(2);
        server.verify();
    }

    /** ③ code!=0 → 抛出并带上 agent-service 的中文提示（作业侧据此写 last_error 并重试）。 */
    @Test
    void decomposeDraft_throws_with_chinese_message_when_code_nonzero() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        // 40103 = agent-service AuthError 实码（chat_routes._access_token_from）
        server.expect(requestTo(CHAT_URL))
                .andRespond(withSuccess("{\"code\":40103,\"msg\":\"登录已过期，请重新登录\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(rt).decomposeDraft(7L, "expired-token", "给孙子办生日", 42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("登录已过期");
        server.verify();
    }

    /**
     * ③b 真机取证：A-2 的身份校验失败以 **HTTP 401 + body {code:40103,msg}** 返回
     * （agent-service 全局异常处理器），不是 200+code!=0。客户端必须从错误响应体里
     * 取中文 msg，而不是把 Java 异常串（401 Unauthorized: [...]）当业务原因。
     */
    @Test
    void decomposeDraft_extracts_chinese_msg_from_4xx_error_body() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo(CHAT_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":40103,\"msg\":\"登录已过期，请重新登录\"}"));

        assertThatThrownBy(() -> client(rt).decomposeDraft(7L, "expired", "给孙子办生日", 42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("登录已过期，请重新登录")
                .hasMessageNotContaining("Unauthorized");
        server.verify();
    }

    /** ④ code==0 但 subtasks 为空 → 同样视为失败，防空草稿被当 DONE 推确认链接。 */
    @Test
    void decomposeDraft_treats_empty_subtasks_as_failure() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo(CHAT_URL))
                .andRespond(withSuccess(
                        "{\"code\":0,\"msg\":\"操作成功\",\"data\":{\"task_id\":null,\"subtasks\":[]}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(rt).decomposeDraft(7L, "t", "今天天气不错", 42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("子任务");
        server.verify();
    }

    /** ⑤ 超时常量：A-2 拆解走独立模板，读超时 60 秒、连接超时 5 秒。 */
    @Test
    void decomposeDraft_uses_60s_read_and_5s_connect_timeout_constants() {
        assertThat(AgentCallbackClient.DRAFT_READ_TIMEOUT_MS).isEqualTo(60_000);
        assertThat(AgentCallbackClient.DRAFT_CONNECT_TIMEOUT_MS).isEqualTo(5_000);
    }

    /** ⑤b 行为验证：同一 buildTemplate 配出的模板，读超时到期即抛，不会静默等满 60 秒。 */
    @Test
    void draft_read_timeout_actually_fires() throws IOException {
        try (ServerSocket silent = new ServerSocket()) {
            silent.bind(new InetSocketAddress("127.0.0.1", 0));
            RestTemplate rt = AgentCallbackClient.buildTemplate(1_000, 200);
            long start = System.nanoTime();

            assertThrows(Exception.class, () -> rt.postForObject(
                    "http://127.0.0.1:" + silent.getLocalPort() + "/api/v1/chat",
                    "{\"message\":\"x\"}", String.class));

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).isLessThan(5_000);
        }
    }
}
