package com.familyapp.wechat;

import com.familyapp.wechat.WechatNotifyDtos.WechatNotifyItemResult;
import com.familyapp.wechat.WechatNotifyDtos.WechatNotifyMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Change 12：backend → wechat-service 模板消息客户端。
 * 铁律：任何传输/解析失败返回空结果不抛出；开关关闭零调用。
 */
class WechatNotifyClientTest {

    private WechatNotifyClient client(RestTemplate rt, String base, boolean enabled) {
        return new WechatNotifyClient(rt, base, enabled, "test-secret");
    }

    @Test
    void sendMessages_posts_batch_with_internal_secret_header() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo("http://wx.test/internal/template/send"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Secret", "test-secret"))
                .andExpect(jsonPath("$.messages[0].openid").value("o1"))
                .andExpect(jsonPath("$.messages[0].event").value("task_digest"))
                .andExpect(jsonPath("$.messages[0].params.today_due").value(2))
                .andRespond(withSuccess(
                        "{\"code\":0,\"results\":["
                                + "{\"openid\":\"o1\",\"ok\":true},"
                                + "{\"openid\":\"o2\",\"ok\":false,\"errcode\":43004,\"errmsg\":\"require subscribe\"}"
                                + "]}",
                        MediaType.APPLICATION_JSON));

        List<WechatNotifyItemResult> results = client(rt, "http://wx.test", true).sendMessages(List.of(
                new WechatNotifyMessage("o1", "task_digest", Map.of("today_due", 2, "overdue", 0))));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).ok()).isTrue();
        assertThat(results.get(1).ok()).isFalse();
        assertThat(results.get(1).errcode()).isEqualTo(43004);
        server.verify();
    }

    @Test
    void connection_failure_returns_empty_without_throwing() {
        // 真实 RestTemplate 打不可达端口（9 端口 discard 类，连接被拒立即返回）
        WechatNotifyClient client = new WechatNotifyClient(new RestTemplate(), "http://127.0.0.1:9", true, "s");

        List<WechatNotifyItemResult> results = client.sendMessages(
                List.of(new WechatNotifyMessage("o1", "task_digest", Map.of())));

        assertThat(results).isEmpty();
    }

    @Test
    void server_500_returns_empty_without_throwing() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo("http://wx.test/internal/template/send"))
                .andRespond(withServerError());

        List<WechatNotifyItemResult> results = client(rt, "http://wx.test", true).sendMessages(
                List.of(new WechatNotifyMessage("o1", "task_digest", Map.of())));

        assertThat(results).isEmpty();
        server.verify();
    }

    @Test
    void disabled_client_makes_no_http_call() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();  // 零期望

        List<WechatNotifyItemResult> results = client(rt, "http://wx.test", false).sendMessages(
                List.of(new WechatNotifyMessage("o1", "task_digest", Map.of())));

        assertThat(results).isEmpty();
        server.verify();  // 无任何请求发生
    }

    @Test
    void empty_batch_makes_no_http_call() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();

        assertThat(client(rt, "http://wx.test", true).sendMessages(List.of())).isEmpty();

        server.verify();
    }

    // ===== Change 14 Task 8：客服消息（文本）通道 =====

    @Test
    void sendKefuText_posts_to_kefu_endpoint_with_internal_secret() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo("http://wx.test/internal/kefu/send"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Secret", "test-secret"))
                .andExpect(jsonPath("$.messages[0].openid").value("o1"))
                .andExpect(jsonPath("$.messages[0].content").value("你想办的事，我帮你列好了"))
                .andRespond(withSuccess(
                        "{\"code\":0,\"results\":[{\"openid\":\"o1\",\"ok\":true}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client(rt, "http://wx.test", true).sendKefuText("o1", "你想办的事，我帮你列好了")).isTrue();

        server.verify();
    }

    @Test
    void sendKefuText_returns_false_when_wechat_rejects_without_throwing() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo("http://wx.test/internal/kefu/send"))
                .andRespond(withSuccess(
                        "{\"code\":0,\"results\":[{\"openid\":\"o1\",\"ok\":false,"
                                + "\"errcode\":45015,\"errmsg\":\"reply timeout\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client(rt, "http://wx.test", true).sendKefuText("o1", "正文")).isFalse();

        server.verify();
    }

    @Test
    void sendKefuText_returns_false_when_results_empty() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo("http://wx.test/internal/kefu/send"))
                .andRespond(withSuccess("{\"code\":0,\"results\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client(rt, "http://wx.test", true).sendKefuText("o1", "正文")).isFalse();

        server.verify();
    }

    @Test
    void sendKefuText_returns_false_on_server_error_without_throwing() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo("http://wx.test/internal/kefu/send")).andRespond(withServerError());

        assertThat(client(rt, "http://wx.test", true).sendKefuText("o1", "正文")).isFalse();

        server.verify();
    }

    @Test
    void sendKefuText_unreachable_service_returns_false_without_throwing() {
        WechatNotifyClient client = new WechatNotifyClient(new RestTemplate(), "http://127.0.0.1:9", true, "s");

        assertThat(client.sendKefuText("o1", "正文")).isFalse();
    }

    @Test
    void sendKefuText_disabled_makes_no_http_call() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();

        assertThat(client(rt, "http://wx.test", false).sendKefuText("o1", "正文")).isFalse();

        server.verify();
    }

    @Test
    void sendKefuText_blank_args_make_no_http_call() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        WechatNotifyClient client = client(rt, "http://wx.test", true);

        assertThat(client.sendKefuText(null, "正文")).isFalse();
        assertThat(client.sendKefuText("  ", "正文")).isFalse();
        assertThat(client.sendKefuText("o1", "  ")).isFalse();

        server.verify();
    }
}
