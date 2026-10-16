package com.familyapp.wechat;

import com.familyapp.agent.AgentCallbackClient;
import com.familyapp.agent.AgentCallbackClient.DraftSubtask;
import com.familyapp.agent.DecomposeSession;
import com.familyapp.agent.DecomposeSessionService;
import com.familyapp.agent.DecomposeSessionType;
import com.familyapp.auth.dto.LoginResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 作业侧串接 A-2 拆解测试（Change 14 Task 6b）。
 *
 * <p>钉住四件事：
 * <ol>
 *   <li><b>拆解必须带 session_id</b>：A-2 不带 {@code session_id} 即被 agent-service 判为 B-1 直写
 *       {@code tasks}（design.md D5 的铁律），所以「票据缺失」「草稿 id 为空」两种情况下
 *       一律硬失败，绝不降级外呼；</li>
 *   <li><b>一条消息最多一份草稿</b>：新建后立刻把 {@code session_id} 中转到入站行，
 *       回队重跑时复用，不重复建草稿（否则用户收到多条推送、多个空草稿）；</li>
 *   <li><b>取票据的时机</b>：在<b>建草稿之前</b>。绑定可能在受理与作业之间被解绑，
 *       先取票据可让这种竞态留下一个干净失败，而不是留下一堆空草稿；</li>
 *   <li><b>本层不吞异常</b>：拆解失败只向上抛，重试额度与终态由 {@code WechatIntakeJob} 判定（D8）。</li>
 * </ol>
 *
 * <p>测试形态：纯 Mockito + 真实 {@link WechatIntakeService} 构造，不起 Spring 上下文。
 * 作业状态机由 {@code WechatIntakeJobTest} 负责，落库由 {@code WechatIntakeDraftTest} 负责。
 */
class WechatIntakeDecomposeTest {

    private static final String OPENID = "o-decompose-test";
    private static final Long 家庭 = 9300L;
    private static final Long 用户 = 9301L;
    private static final Long 新草稿 = 9400L;
    private static final String 票据 = "a".repeat(64);
    private static final String 原文 = "周六带爸去复查";

    private WechatService wechatService;
    private WechatInboundMessageRepository repository;
    private DecomposeSessionService sessionService;
    private AgentCallbackClient agentClient;
    private WechatIntakeService service;

    @BeforeEach
    void setUp() {
        wechatService = mock(WechatService.class);
        repository = mock(WechatInboundMessageRepository.class);
        sessionService = mock(DecomposeSessionService.class);
        agentClient = mock(AgentCallbackClient.class);
        // 拆解编排与回推无关，给一个永不送达的替身即可（话术与推送行为由 WechatIntakeNotifyTest 把关）
        service = new WechatIntakeService(wechatService, repository, sessionService, agentClient,
            mock(WechatNotifyClient.class), "http://h5.test", true);

        绑定用户(票据);
        建草稿返回(新草稿);
        when(agentClient.decomposeDraft(anyLong(), anyString(), anyString(), anyLong()))
            .thenReturn(List.of(new DraftSubtask("挂号", "预约心内科门诊", 1), new DraftSubtask("准备病历", null, 2)));
    }

    private void 绑定用户(String accessToken) {
        when(wechatService.loginByOpenid(OPENID)).thenReturn(Optional.of(
            new LoginResponse(accessToken, 用户, "小美", List.of())));
    }

    private void 建草稿返回(Long sessionId) {
        DecomposeSession draft = new DecomposeSession();
        draft.setId(sessionId);
        draft.setFamilyId(家庭);
        when(sessionService.createDraft(anyString(), any(Map.class), anyLong(), anyLong())).thenReturn(draft);
    }

    private WechatInboundMessage 入站行() {
        WechatInboundMessage row = new WechatInboundMessage();
        row.setMsgId("decompose-1");
        row.setOpenid(OPENID);
        row.setContent(原文);
        row.setReceivedAt(Instant.now());
        row.setStatus(WechatInboundStatus.RUNNING);
        row.setAttempts(1);
        row.setFamilyId(家庭);
        row.setUserId(用户);
        return row;
    }

    @Test
    @DisplayName("① 成功链路：以本次新建草稿的 session_id 调 A-2，四参数逐一钉死")
    void callsAgentWithFreshDraftId() {
        Long sessionId = service.decomposeInbound(入站行());

        assertThat(sessionId).isEqualTo(新草稿);
        ArgumentCaptor<Long> familyCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> sessionCaptor = ArgumentCaptor.forClass(Long.class);
        verify(agentClient).decomposeDraft(familyCaptor.capture(), tokenCaptor.capture(),
            messageCaptor.capture(), sessionCaptor.capture());

        assertThat(familyCaptor.getValue()).isEqualTo(家庭);
        assertThat(tokenCaptor.getValue()).isEqualTo(票据);
        assertThat(messageCaptor.getValue()).isEqualTo(原文);
        assertThat(sessionCaptor.getValue()).isEqualTo(新草稿);
    }

    @Test
    @DisplayName("② 票据来自行上 openid 的绑定用户，不是硬编码或入站行缓存的 userId")
    void mintTokenFromRowOpenid() {
        WechatInboundMessage row = 入站行();
        row.setOpenid("o-another-openid");
        when(wechatService.loginByOpenid("o-another-openid")).thenReturn(Optional.of(
            new LoginResponse("b".repeat(64), 用户, "小美", List.of())));

        service.decomposeInbound(row);

        verify(wechatService).loginByOpenid("o-another-openid");
        verify(agentClient).decomposeDraft(eq(家庭), eq("b".repeat(64)), eq(原文), eq(新草稿));
    }

    @Test
    @DisplayName("③ 行上已有 session_id → 复用草稿不重建，也不重复中转")
    void reusesExistingDraftOnRetry() {
        WechatInboundMessage row = 入站行();
        row.setSessionId(777L);

        Long sessionId = service.decomposeInbound(row);

        assertThat(sessionId).isEqualTo(777L);
        verify(sessionService, never()).createDraft(anyString(), any(Map.class), anyLong(), anyLong());
        verify(repository, never()).attachSession(anyString(), anyLong(), any(Instant.class));
        verify(agentClient).decomposeDraft(eq(家庭), eq(票据), eq(原文), eq(777L));
    }

    @Test
    @DisplayName("④ 新建草稿后立即将 session_id 中转到入站行（回队重跑才认得这份草稿）")
    void attachesDraftIdToRowBeforeCallingAgent() {
        service.decomposeInbound(入站行());

        verify(repository).attachSession(eq("decompose-1"), eq(新草稿), any(Instant.class));
    }

    @Test
    @DisplayName("⑤ 建草稿用的仍是受理侧裁决出的 family_id / user_id（硬约束 4）")
    void createsDraftUnderAdjudicatedFamily() {
        service.decomposeInbound(入站行());

        verify(sessionService).createDraft(eq(DecomposeSessionType.TASK_CREATE.name()),
            eq(Map.of("title", 原文)), eq(家庭), eq(用户));
    }

    @Test
    @DisplayName("⑥ 绑定已失效（取不到票据）→ 中文硬失败，既不建草稿也不调 A-2")
    void missingTokenFailsBeforeCreatingDraft() {
        when(wechatService.loginByOpenid(OPENID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decomposeInbound(入站行()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("绑定")
            .hasMessageContaining("公众号");

        verify(sessionService, never()).createDraft(anyString(), any(Map.class), anyLong(), anyLong());
        verify(agentClient, never()).decomposeDraft(anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("⑦ 票据为空白串视同失效（被 logout 清过但补发失败的边界）")
    void blankTokenIsTreatedAsMissing() {
        绑定用户("   ");

        assertThatThrownBy(() -> service.decomposeInbound(入站行()))
            .isInstanceOf(IllegalStateException.class);

        verify(agentClient, never()).decomposeDraft(anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("⑧ 草稿 id 为空 → 绝不以 null session_id 外呼（那等于走 B-1 直写 tasks）")
    void neverCallsAgentWithoutSessionId() {
        建草稿返回(null);

        assertThatThrownBy(() -> service.decomposeInbound(入站行()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("草稿");

        verify(agentClient, never()).decomposeDraft(anyLong(), anyString(), anyString(), anyLong());
        verify(agentClient, never()).decomposeDraft(isNull(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("⑨ A-2 抛异常时原样上抛（重试与终态判定归作业侧，本层不吞不转译）")
    void propagatesAgentFailureForJobLevelRetry() {
        when(agentClient.decomposeDraft(anyLong(), anyString(), anyString(), anyLong()))
            .thenThrow(new IllegalStateException("草稿拆解失败：登录已过期，请重新登录"));

        assertThatThrownBy(() -> service.decomposeInbound(入站行()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageEndingWith("登录已过期，请重新登录");
    }

    @Test
    @DisplayName("⑩ 拆解返回空列表也视为失败（防御：客户端已抛，这里不允许第二道口子漏过）")
    void treatsEmptySubtaskListAsFailure() {
        when(agentClient.decomposeDraft(anyLong(), anyString(), anyString(), anyLong()))
            .thenReturn(List.of());

        assertThatThrownBy(() -> service.decomposeInbound(入站行()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("子任务");
    }
}
