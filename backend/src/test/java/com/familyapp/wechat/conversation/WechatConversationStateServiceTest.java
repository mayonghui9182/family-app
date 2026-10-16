package com.familyapp.wechat.conversation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static com.familyapp.wechat.conversation.ConversationStatus.ACTIVE;
import static com.familyapp.wechat.conversation.ConversationStatus.AWAITING_CONFIRMATION;
import static com.familyapp.wechat.conversation.ConversationStatus.CANCELLED;
import static com.familyapp.wechat.conversation.ConversationStatus.CONFIRMED;
import static com.familyapp.wechat.conversation.ConversationStatus.EXPIRED;
import static com.familyapp.wechat.conversation.ConversationStatus.PAUSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WechatConversationStateServiceTest {

    private static final long BIND_ID = 71L;
    private static final long USER_ID = 81L;
    private static final long FAMILY_A = 11L;
    private static final long FAMILY_B = 22L;
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");

    @Mock
    private WechatTaskConversationRepository repository;
    @Mock
    private WechatFamilyAccessGuard accessGuard;

    private final Map<Long, WechatTaskConversation> activeByFamily = new HashMap<>();
    private final Map<Long, WechatTaskConversation> byId = new HashMap<>();
    private WechatConversationProperties properties;
    private WechatConversationStateService service;

    @BeforeEach
    void setUp() {
        properties = new WechatConversationProperties();
        lenient().when(accessGuard.findAccess(anyLong(), anyLong()))
                .thenReturn(Optional.of(new WechatFamilyAccessGuard.FamilyAccess(
                        FAMILY_A,
                        "当前家庭",
                        com.familyapp.family.FamilyRole.ADULT,
                        "成人成员")));
        lenient().when(repository.findActiveForUpdate(anyLong(), anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(
                        activeByFamily.get(invocation.getArgument(1))));
        lenient().when(repository.findByIdAndFamilyId(anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    WechatTaskConversation conversation =
                            byId.get(invocation.getArgument(0));
                    Long familyId = invocation.getArgument(1);
                    if (conversation == null
                            || !familyId.equals(conversation.getFamilyId())) {
                        return Optional.empty();
                    }
                    return Optional.of(conversation);
                });
        lenient().when(repository.save(any(WechatTaskConversation.class)))
                .thenAnswer(invocation -> track(invocation.getArgument(0)));
        lenient().when(repository.saveAndFlush(any(WechatTaskConversation.class)))
                .thenAnswer(invocation -> track(invocation.getArgument(0)));

        service = new WechatConversationStateService(
                repository,
                properties,
                accessGuard,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void switchingFamilyPausesSourceAndRestoresTargetPreviousStatus() {
        WechatTaskConversation source = conversation(
                201L,
                FAMILY_A,
                ACTIVE,
                null,
                NOW.plusSeconds(3600));
        WechatTaskConversation target = conversation(
                202L,
                FAMILY_B,
                PAUSED,
                AWAITING_CONFIRMATION,
                NOW.plusSeconds(3600));
        register(source);
        register(target);

        WechatTaskConversation result =
                service.switchFamily(BIND_ID, USER_ID, FAMILY_A, FAMILY_B);

        assertThat(source.getStatus()).isEqualTo(PAUSED);
        assertThat(source.getPausedFromStatus()).isEqualTo(ACTIVE);
        assertThat(source.getActiveSlot()).isEqualTo((short) 1);
        assertThat(target.getStatus()).isEqualTo(AWAITING_CONFIRMATION);
        assertThat(target.getPausedFromStatus()).isNull();
        assertThat(target.getActiveSlot()).isEqualTo((short) 1);
        assertThat(target.getExpiresAt()).isEqualTo(NOW.plusSeconds(24 * 3600));
        assertThat(result).isSameAs(target);
    }

    @Test
    void startNewTaskCancelsOldConversationAndUsesConfiguredTtl() {
        properties.setTtlHours(36);
        WechatTaskConversation old = conversation(
                203L,
                FAMILY_A,
                AWAITING_CONFIRMATION,
                null,
                NOW.plusSeconds(3600));
        old.setCurrentRevision(4);
        old.setSlotState(new LinkedHashMap<>(Map.of("candidate", "旧候选")));
        register(old);

        WechatTaskConversation created =
                service.startNewConversation(BIND_ID, USER_ID, FAMILY_A);

        assertThat(old.getStatus()).isEqualTo(CANCELLED);
        assertThat(old.getActiveSlot()).isNull();
        assertThat(old.getSlotState())
                .containsEntry("_system_end_reason", "NEW_CONVERSATION");
        assertThat(created).isNotSameAs(old);
        assertThat(created.getStatus()).isEqualTo(ACTIVE);
        assertThat(created.getCurrentRevision()).isZero();
        assertThat(created.getSlotState()).isEmpty();
        assertThat(created.getExpiresAt()).isEqualTo(NOW.plusSeconds(36 * 3600));
        assertThat(activeByFamily.get(FAMILY_A)).isSameAs(created);
    }

    @Test
    void expiredPausedCandidateIsNotRestored() {
        WechatTaskConversation expired = conversation(
                204L,
                FAMILY_A,
                PAUSED,
                AWAITING_CONFIRMATION,
                NOW.minusSeconds(1));
        expired.setCurrentRevision(7);
        expired.setSlotState(new LinkedHashMap<>(Map.of("candidate", "过期候选")));
        register(expired);

        WechatTaskConversation created =
                service.openOrResume(BIND_ID, USER_ID, FAMILY_A);

        assertThat(expired.getStatus()).isEqualTo(EXPIRED);
        assertThat(expired.getPausedFromStatus()).isNull();
        assertThat(expired.getActiveSlot()).isNull();
        assertThat(expired.getSlotState())
                .containsEntry("_system_end_reason", "TTL_EXPIRED");
        assertThat(created).isNotSameAs(expired);
        assertThat(created.getStatus()).isEqualTo(ACTIVE);
        assertThat(created.getCurrentRevision()).isZero();
        assertThat(created.getSlotState()).isEmpty();
        assertThat(activeByFamily.get(FAMILY_A)).isSameAs(created);
    }

    @Test
    void candidateModificationAndConfirmationFollowAllowedTransitions() {
        WechatTaskConversation conversation = conversation(
                205L,
                FAMILY_A,
                ACTIVE,
                null,
                NOW.plusSeconds(3600));
        register(conversation);

        assertThat(service.awaitConfirmation(205L, FAMILY_A).getStatus())
                .isEqualTo(AWAITING_CONFIRMATION);
        assertThat(service.returnToActive(205L, FAMILY_A).getStatus())
                .isEqualTo(ACTIVE);
        service.awaitConfirmation(205L, FAMILY_A);
        WechatTaskConversation confirmed =
                service.confirm(205L, FAMILY_A, USER_ID);

        assertThat(confirmed.getStatus()).isEqualTo(CONFIRMED);
        assertThat(confirmed.getActiveSlot()).isNull();
        assertThat(activeByFamily).doesNotContainKey(FAMILY_A);
    }

    @Test
    void removedMemberCannotResumePausedConversation() {
        when(accessGuard.requireAccess(
                USER_ID,
                FAMILY_A,
                WechatFamilyAccessGuard.AccessOperation.RESUME_CONVERSATION))
                .thenThrow(new WechatFamilyAccessDeniedException());

        assertThatThrownBy(() ->
                service.openOrResume(BIND_ID, USER_ID, FAMILY_A))
                .isInstanceOf(WechatFamilyAccessDeniedException.class);
        verify(repository, never()).findActiveForUpdate(BIND_ID, FAMILY_A);
    }

    @Test
    void revokedCurrentFamilyIsTerminatedAndHistoryIsNotRead() {
        WechatTaskConversation conversation = conversation(
                206L,
                FAMILY_A,
                AWAITING_CONFIRMATION,
                null,
                NOW.plusSeconds(3600));
        conversation.setRollingSummary("不应返回的旧家庭摘要");
        register(conversation);
        when(accessGuard.findAccess(USER_ID, FAMILY_A))
                .thenReturn(Optional.empty());

        boolean invalidated =
                service.invalidateIfAccessRevoked(BIND_ID, USER_ID, FAMILY_A);

        assertThat(invalidated).isTrue();
        assertThat(conversation.getStatus()).isEqualTo(CANCELLED);
        assertThat(conversation.getActiveSlot()).isNull();
        assertThat(conversation.getSlotState())
                .containsEntry("_system_end_reason", "FAMILY_ACCESS_REVOKED");

        when(accessGuard.requireAccess(
                USER_ID,
                FAMILY_A,
                WechatFamilyAccessGuard.AccessOperation.READ_HISTORY))
                .thenThrow(new WechatFamilyAccessDeniedException());

        assertThatThrownBy(() ->
                service.listHistory(BIND_ID, USER_ID, FAMILY_A))
                .isInstanceOf(WechatFamilyAccessDeniedException.class);
        verify(repository, never())
                .findTop5ByWechatBindIdAndFamilyIdAndDeletedAtIsNullOrderByLastActivityAtDesc(
                        eq(BIND_ID),
                        eq(FAMILY_A));
    }

    private WechatTaskConversation track(WechatTaskConversation conversation) {
        if (conversation.getActiveSlot() == null) {
            activeByFamily.remove(conversation.getFamilyId(), conversation);
        } else {
            activeByFamily.put(conversation.getFamilyId(), conversation);
        }
        if (conversation.getId() != null) {
            byId.put(conversation.getId(), conversation);
        }
        return conversation;
    }

    private void register(WechatTaskConversation conversation) {
        byId.put(conversation.getId(), conversation);
        activeByFamily.put(conversation.getFamilyId(), conversation);
    }

    private WechatTaskConversation conversation(
            long id,
            long familyId,
            ConversationStatus status,
            ConversationStatus pausedFrom,
            Instant expiresAt) {
        WechatTaskConversation conversation = new WechatTaskConversation();
        ReflectionTestUtils.setField(conversation, "id", id);
        conversation.setWechatBindId(BIND_ID);
        conversation.setUserId(USER_ID);
        conversation.setFamilyId(familyId);
        conversation.setStatus(status);
        conversation.setPausedFromStatus(pausedFrom);
        conversation.setActiveSlot((short) 1);
        conversation.setCurrentRevision(0);
        conversation.setSlotState(new LinkedHashMap<>());
        conversation.setLastActivityAt(NOW.minusSeconds(60));
        conversation.setExpiresAt(expiresAt);
        return conversation;
    }
}
