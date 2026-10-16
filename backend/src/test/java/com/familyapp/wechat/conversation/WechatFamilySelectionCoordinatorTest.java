package com.familyapp.wechat.conversation;

import com.familyapp.family.FamilyRole;
import com.familyapp.wechat.conversation.WechatFamilyRoutingService.FamilyOption;
import com.familyapp.wechat.conversation.WechatFamilyRoutingService.FamilySelectionChallenge;
import com.familyapp.wechat.conversation.WechatFamilyRoutingService.RouteDecision;
import com.familyapp.wechat.conversation.WechatFamilyRoutingService.RouteOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.familyapp.wechat.conversation.MessageProcessingStatus.PENDING;
import static com.familyapp.wechat.conversation.MessageProcessingStatus.PROCESSED;
import static com.familyapp.wechat.conversation.MessageProcessingStatus.WAITING_FAMILY;
import static com.familyapp.wechat.conversation.WechatFamilySelectionCoordinator.ActionType.CHALLENGE;
import static com.familyapp.wechat.conversation.WechatFamilySelectionCoordinator.ActionType.NO_CHALLENGE;
import static com.familyapp.wechat.conversation.WechatFamilySelectionCoordinator.ActionType.REJECTED;
import static com.familyapp.wechat.conversation.WechatFamilySelectionCoordinator.ActionType.REPLAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WechatFamilySelectionCoordinatorTest {

    private static final long BIND_ID = 71L;
    private static final long USER_ID = 81L;
    private static final long ORIGINAL_ID = 101L;
    private static final long SELECTION_ID = 102L;
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");

    @Mock
    private WechatConversationMessageRepository messageRepository;
    @Mock
    private WechatChatContextRepository contextRepository;
    @Mock
    private WechatFamilyRoutingService routingService;

    private final Map<Long, WechatConversationMessage> messages = new LinkedHashMap<>();
    private WechatChatContext context;
    private WechatFamilySelectionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        context = new WechatChatContext();
        context.setWechatBindId(BIND_ID);
        context.setUserId(USER_ID);
        context.setRouteRevision(0L);

        when(messageRepository.findByIdAndWechatBindId(any(Long.class), eq(BIND_ID)))
                .thenAnswer(invocation -> Optional.ofNullable(
                        messages.get(invocation.getArgument(0))));
        when(messageRepository.save(any(WechatConversationMessage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(contextRepository.findByWechatBindIdForUpdate(BIND_ID))
                .thenReturn(Optional.of(context));
        when(contextRepository.save(any(WechatChatContext.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        coordinator = new WechatFamilySelectionCoordinator(
                messageRepository,
                contextRepository,
                routingService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void selectedFamilyReplaysOriginalTextExactlyOnceWithoutResend() {
        WechatConversationMessage original = message(ORIGINAL_ID, "帮我安排周末买菜");
        WechatConversationMessage selection = message(SELECTION_ID, "2");
        messages.put(ORIGINAL_ID, original);
        messages.put(SELECTION_ID, selection);

        FamilyOption first = option(11L, "一一家");
        FamilyOption second = option(22L, "二二家");
        FamilySelectionChallenge challenge = new FamilySelectionChallenge(
                BIND_ID,
                1L,
                NOW.plusSeconds(300),
                List.of(11L, 22L));
        when(routingService.automaticSelection(BIND_ID, null, null))
                .thenReturn(new RouteDecision(
                        RouteOutcome.CHALLENGE,
                        null,
                        List.of(first, second),
                        challenge));
        when(routingService.resolveSelection(
                eq(BIND_ID),
                eq(1L),
                any(FamilySelectionChallenge.class),
                anyString(),
                eq(NOW)))
                .thenReturn(new RouteDecision(
                        RouteOutcome.SELECTED,
                        second,
                        List.of(first, second),
                        null));

        var waiting = coordinator.routeMessage(BIND_ID, ORIGINAL_ID);
        var replay = coordinator.applySelection(BIND_ID, SELECTION_ID, "2");
        var repeated = coordinator.applySelection(BIND_ID, SELECTION_ID, "2");

        assertThat(waiting.type()).isEqualTo(CHALLENGE);
        assertThat(original.getProcessingStatus()).isEqualTo(PENDING);
        assertThat(original.getFamilyId()).isEqualTo(22L);
        assertThat(replay.type()).isEqualTo(REPLAY);
        assertThat(replay.messageId()).isEqualTo(ORIGINAL_ID);
        assertThat(replay.familyId()).isEqualTo(22L);
        assertThat(selection.getProcessingStatus()).isEqualTo(PROCESSED);
        assertThat(selection.getFamilyId()).isEqualTo(22L);
        assertThat(context.getCurrentFamilyId()).isEqualTo(22L);
        assertThat(context.getPendingFamilySelection()).isNull();
        assertThat(context.getRouteRevision()).isEqualTo(2L);
        assertThat(repeated.type()).isEqualTo(NO_CHALLENGE);
        verify(routingService, times(1)).resolveSelection(
                eq(BIND_ID),
                eq(1L),
                any(FamilySelectionChallenge.class),
                eq("2"),
                eq(NOW));
    }

    @Test
    void tamperedSnapshotIsRejectedWithoutReplayingOriginal() {
        WechatConversationMessage original = message(ORIGINAL_ID, "创建家庭任务");
        WechatConversationMessage selection = message(SELECTION_ID, "1");
        messages.put(ORIGINAL_ID, original);
        messages.put(SELECTION_ID, selection);

        FamilyOption first = option(11L, "一一家");
        FamilyOption second = option(22L, "二二家");
        FamilySelectionChallenge challenge = new FamilySelectionChallenge(
                BIND_ID,
                1L,
                NOW.plusSeconds(300),
                List.of(11L, 22L));
        when(routingService.automaticSelection(BIND_ID, null, null))
                .thenReturn(new RouteDecision(
                        RouteOutcome.CHALLENGE,
                        null,
                        List.of(first, second),
                        challenge));

        coordinator.routeMessage(BIND_ID, ORIGINAL_ID);
        context.getPendingFamilySelection().put("route_revision", 999L);

        var result = coordinator.applySelection(BIND_ID, SELECTION_ID, "1");

        assertThat(result.type()).isEqualTo(REJECTED);
        assertThat(original.getProcessingStatus()).isEqualTo(WAITING_FAMILY);
        assertThat(original.getFamilyId()).isNull();
        assertThat(selection.getProcessingStatus()).isEqualTo(PROCESSED);
        assertThat(context.getPendingFamilySelection()).isNull();
        assertThat(context.getRouteRevision()).isEqualTo(2L);
        verify(routingService, never()).resolveSelection(
                any(Long.class),
                any(Long.class),
                any(FamilySelectionChallenge.class),
                anyString(),
                any(Instant.class));
    }

    @Test
    void expiredSelectionIsConsumedButOriginalNeverReachesAiQueue() {
        WechatConversationMessage original = message(ORIGINAL_ID, "过期后不能创建任务");
        original.setProcessingStatus(WAITING_FAMILY);
        WechatConversationMessage selection = message(SELECTION_ID, "1");
        messages.put(ORIGINAL_ID, original);
        messages.put(SELECTION_ID, selection);

        context.setRouteRevision(7L);
        context.setPendingFamilySelection(pendingSnapshot(
                ORIGINAL_ID,
                7L,
                NOW.minusSeconds(1),
                List.of(11L, 22L)));
        when(routingService.resolveSelection(
                eq(BIND_ID),
                eq(7L),
                any(FamilySelectionChallenge.class),
                eq("1"),
                eq(NOW)))
                .thenReturn(new RouteDecision(
                        RouteOutcome.EXPIRED,
                        null,
                        List.of(),
                        null));

        var result = coordinator.applySelection(BIND_ID, SELECTION_ID, "1");

        assertThat(result.type()).isEqualTo(REJECTED);
        assertThat(original.getProcessingStatus()).isEqualTo(WAITING_FAMILY);
        assertThat(original.getFamilyId()).isNull();
        assertThat(selection.getProcessingStatus()).isEqualTo(PROCESSED);
        assertThat(context.getPendingFamilySelection()).isNull();
        assertThat(context.getRouteRevision()).isEqualTo(8L);
    }

    private WechatConversationMessage message(long id, String content) {
        WechatConversationMessage message = new WechatConversationMessage();
        ReflectionTestUtils.setField(message, "id", id);
        message.setWechatAccountId("official-1");
        message.setExternalMsgId("msg-" + id);
        message.setOpenid("openid-1");
        message.setDirection(MessageDirection.INBOUND);
        message.setContent(content);
        message.setWechatBindId(BIND_ID);
        message.setUserId(USER_ID);
        message.setProcessingStatus(PENDING);
        message.setAvailableAt(NOW);
        return message;
    }

    private FamilyOption option(long familyId, String name) {
        return new FamilyOption(
                familyId,
                name,
                FamilyRole.ADULT,
                FamilyRole.ADULT.getChineseName());
    }

    private Map<String, Object> pendingSnapshot(
            long pendingMessageId,
            long revision,
            Instant expiresAt,
            List<Long> familyIds) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("pending_message_id", pendingMessageId);
        value.put("wechat_bind_id", BIND_ID);
        value.put("route_revision", revision);
        value.put("expires_at", expiresAt.toString());
        value.put("candidate_family_ids", familyIds);
        return value;
    }
}
