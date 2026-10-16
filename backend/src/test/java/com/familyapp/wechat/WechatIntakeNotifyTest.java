package com.familyapp.wechat;

import com.familyapp.agent.AgentCallbackClient;
import com.familyapp.agent.DecomposeSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拆解结果回推话术测试（Change 14 Task 8）。
 *
 * <p>钉住四件事：
 * <ol>
 *   <li><b>深链落点必须是真实路由</b>：{@code /wechat/authorize}（{@code wechat_routes.py} 的 OAuth 入口，
 *       design.md D7 行文里的 {@code /wechat/oauth} 全仓不存在）；{@code next} 指向
 *       {@code /agent/draft/{id}}，由 Task 10 加白名单、Task 12 建页面；</li>
 *   <li><b>话术只讲人话</b>：中文、第二人称、老人可读，SHALL NOT 出现 session/proposal/agent/error 等术语。
 *       术语断言<b>先剥掉 URL</b>——深链路径里的 {@code agent} 属路由契约，不是话术用词；</li>
 *   <li><b>回推是旁路</b>：{@link WechatNotifyClient} 抛任何异常都在本层收口，绝不冒泡改动作业终态（D6）；</li>
 *   <li><b>推不出去就不推</b>：缺 openid 或缺 session_id 时零调用，
 *       宁可不通知，也不推一条「帮你列好了」却没有确认入口的消息。</li>
 * </ol>
 *
 * <p>测试形态：纯 Mockito + 真实 {@link WechatIntakeService}，不起 Spring 上下文；
 * 只有用例③ 用真实 {@link WechatNotifyClient}（挂 {@link MockRestServiceServer}）证明
 * {@code wechat.notify.enabled=false} 时<b>零 HTTP</b>——闸门在客户端，本层只负责不绕过它。
 */
class WechatIntakeNotifyTest {

    private static final String OPENID = "o-notify-test";
    private static final String H5 = "http://h5.test";
    private static final Long 草稿指针 = 4242L;
    /** 英文术语黑名单（大小写不敏感），与 spec.md「话术 SHALL NOT 出现」逐字对齐。 */
    private static final String 术语黑名单 = "(?i)(session|proposal|agent|error)";

    private WechatNotifyClient notifyClient;
    private WechatIntakeService service;

    @BeforeEach
    void setUp() {
        notifyClient = mock(WechatNotifyClient.class);
        when(notifyClient.sendKefuText(anyString(), anyString())).thenReturn(true);
        service = new WechatIntakeService(
            mock(WechatService.class), mock(WechatInboundMessageRepository.class),
            mock(DecomposeSessionService.class), mock(AgentCallbackClient.class),
            notifyClient, H5, true);
    }

    private WechatInboundMessage 入站行() {
        WechatInboundMessage row = new WechatInboundMessage();
        row.setMsgId("notify-1");
        row.setOpenid(OPENID);
        row.setContent("周六带爸去复查");
        row.setReceivedAt(Instant.now());
        row.setStatus(WechatInboundStatus.RUNNING);
        row.setAttempts(1);
        row.setFamilyId(9300L);
        row.setUserId(9301L);
        return row;
    }

    /** 捕获本次回推的正文，并顺带断言「只推一条」。 */
    private String 推送内容() {
        ArgumentCaptor<String> 内容 = ArgumentCaptor.forClass(String.class);
        verify(notifyClient).sendKefuText(eq(OPENID), 内容.capture());
        return 内容.getValue();
    }

    /** 剥掉 URL 后再查术语：深链路径不可改，话术正文才受术语铁律约束。 */
    private String 剥链(String 文本) {
        return 文本.replaceAll("https?://\\S+", "");
    }

    @Test
    @DisplayName("① 拆解成功 → 推「已列好 + 点链接确认」，链接是 {h5}/wechat/authorize?next=/agent/draft/{id}")
    void done_pushesPasswordlessDeepLinkToDraftPage() {
        service.notifyDone(入站行(), 草稿指针);

        String content = 推送内容();
        assertThat(content).contains(H5 + "/wechat/authorize?next=/agent/draft/" + 草稿指针);
        assertThat(content).contains("确认");
    }

    @Test
    @DisplayName("② h5 基址误配成带尾斜杠 → 拼出的链接不出现双斜杠")
    void h5BaseUrlWithTrailingSlashIsNormalized() {
        WechatIntakeService 尾斜杠服务 = new WechatIntakeService(
            mock(WechatService.class), mock(WechatInboundMessageRepository.class),
            mock(DecomposeSessionService.class), mock(AgentCallbackClient.class),
            notifyClient, "http://h5.test/", true);

        尾斜杠服务.notifyDone(入站行(), 草稿指针);

        assertThat(推送内容()).doesNotContain("h5.test//wechat");
    }

    @Test
    @DisplayName("③ 拆解失败（重试耗尽）→ 推「没太看懂」兜底话术，且不含任何链接")
    void failed_pushesPlainFallbackWithoutLink() {
        service.notifyFailed(入站行());

        String content = 推送内容();
        assertThat(content).contains("没太看懂");
        assertThat(content).doesNotContain("http");
    }

    @Test
    @DisplayName("④ AI 开关关闭 → 推「暂时关闭，先在应用里手动添加任务」，且不含链接（D8）")
    void aiDisabled_pushesGuidanceWithoutLink() {
        service.notifyAiDisabled(入站行());

        String content = 推送内容();
        assertThat(content).contains("暂时关闭");
        assertThat(content).contains("手动添加");
        assertThat(content).doesNotContain("http");
    }

    @Test
    @DisplayName("⑤ 三条话术剥掉链接后不含英文术语（老人可读铁律）")
    void allTalkTracksAreFreeOfEnglishJargon() {
        service.notifyDone(入站行(), 草稿指针);
        service.notifyFailed(入站行());
        service.notifyAiDisabled(入站行());

        ArgumentCaptor<String> 内容 = ArgumentCaptor.forClass(String.class);
        verify(notifyClient, times(3))
            .sendKefuText(eq(OPENID), 内容.capture());
        assertThat(内容.getAllValues()).hasSize(3);
        for (String each : 内容.getAllValues()) {
            assertThat(剥链(each)).doesNotContainPattern(术语黑名单);
        }
    }

    @Test
    @DisplayName("⑥ 回推开关关闭（真实客户端）→ 零 HTTP 调用，且照常返回不抛")
    void notifyDisabled_makesZeroHttpRequest() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();  // 零期望
        WechatIntakeService 关闭态服务 = new WechatIntakeService(
            mock(WechatService.class), mock(WechatInboundMessageRepository.class),
            mock(DecomposeSessionService.class), mock(AgentCallbackClient.class),
            new WechatNotifyClient(restTemplate, "http://wx.test", false, "test-secret"), H5, true);

        关闭态服务.notifyDone(入站行(), 草稿指针);
        关闭态服务.notifyFailed(入站行());

        server.verify();
    }

    @Test
    @DisplayName("⑦ 客户端抛异常 → 本层收口不冒泡（推链接失败绝不回滚拆解结果）")
    void clientThrows_isSwallowed() {
        when(notifyClient.sendKefuText(anyString(), anyString()))
            .thenThrow(new IllegalStateException("客服消息发送炸了"));

        assertThatCode(() -> {
            service.notifyDone(入站行(), 草稿指针);
            service.notifyFailed(入站行());
            service.notifyAiDisabled(入站行());
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⑧ 客户端返回 false（微信侧拒绝，如 45015 超 48h 窗口）→ 只记日志，不抛不重试")
    void clientRejects_isNotRetriedAndNotThrown() {
        when(notifyClient.sendKefuText(anyString(), anyString())).thenReturn(false);

        assertThatCode(() -> service.notifyDone(入站行(), 草稿指针)).doesNotThrowAnyException();
        verify(notifyClient).sendKefuText(eq(OPENID), anyString());
    }

    @Test
    @DisplayName("⑨ session_id 缺失 → 零推送：宁可静默，也不推一条没有确认入口的消息")
    void missingSessionId_pushesNothing() {
        service.notifyDone(入站行(), null);

        verify(notifyClient, never()).sendKefuText(anyString(), anyString());
    }

    @Test
    @DisplayName("⑩ openid 为空白 → 零推送（不拿 null 去拼 HTTP body）")
    void blankOpenid_pushesNothing() {
        WechatInboundMessage 无openid = 入站行();
        无openid.setOpenid("   ");
        service.notifyDone(无openid, 草稿指针);
        service.notifyFailed(无openid);
        service.notifyAiDisabled(无openid);

        verify(notifyClient, never()).sendKefuText(anyString(), anyString());
    }
}
