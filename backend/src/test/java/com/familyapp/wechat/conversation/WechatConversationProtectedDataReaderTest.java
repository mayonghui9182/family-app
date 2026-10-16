package com.familyapp.wechat.conversation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WechatConversationProtectedDataReaderTest {

    private static final long BIND_ID = 71L;
    private static final long USER_ID = 81L;
    private static final long FAMILY_ID = 11L;

    @Mock
    private WechatFamilyAccessGuard accessGuard;
    @Mock
    private WechatTaskConversationRepository conversationRepository;
    @Mock
    private WechatCandidateVersionRepository candidateRepository;
    @Mock
    private FamilyTaskExperienceRepository experienceRepository;

    private WechatConversationProtectedDataReader reader;

    @BeforeEach
    void setUp() {
        reader = new WechatConversationProtectedDataReader(
                accessGuard,
                conversationRepository,
                candidateRepository,
                experienceRepository);
    }

    @Test
    void revokedFamilyCannotReturnSummaryCandidateOrExperience() {
        when(accessGuard.requireAccess(
                anyLong(),
                anyLong(),
                any(WechatFamilyAccessGuard.AccessOperation.class)))
                .thenThrow(new WechatFamilyAccessDeniedException());

        assertThatThrownBy(() ->
                reader.listHistory(BIND_ID, USER_ID, FAMILY_ID))
                .isInstanceOf(WechatFamilyAccessDeniedException.class);
        assertThatThrownBy(() ->
                reader.findCandidate(201L, 3, USER_ID, FAMILY_ID))
                .isInstanceOf(WechatFamilyAccessDeniedException.class);
        assertThatThrownBy(() ->
                reader.listActiveExperiences(USER_ID, FAMILY_ID))
                .isInstanceOf(WechatFamilyAccessDeniedException.class);

        verify(conversationRepository, never())
                .findTop5ByWechatBindIdAndFamilyIdAndDeletedAtIsNullOrderByLastActivityAtDesc(
                        any(Long.class),
                        any(Long.class));
        verify(candidateRepository, never())
                .findByConversationIdAndFamilyIdAndRevision(
                        any(Long.class),
                        any(Long.class),
                        any(Integer.class));
        verify(experienceRepository, never())
                .findByFamilyIdAndStatusOrderByUpdatedAtDesc(
                        any(Long.class),
                        any(ExperienceStatus.class));
    }
}
