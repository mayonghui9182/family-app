package com.familyapp.wechat.conversation;

import com.familyapp.family.Family;
import com.familyapp.family.FamilyMember;
import com.familyapp.family.FamilyMemberRepository;
import com.familyapp.family.FamilyRepository;
import com.familyapp.family.FamilyRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static com.familyapp.wechat.conversation.WechatFamilyAccessGuard.AccessOperation.CONFIRM_TASK;
import static com.familyapp.wechat.conversation.WechatFamilyAccessGuard.AccessOperation.READ_HISTORY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WechatFamilyAccessGuardTest {

    private static final long USER_ID = 81L;
    private static final long FAMILY_ID = 11L;

    @Mock
    private FamilyMemberRepository memberRepository;
    @Mock
    private FamilyRepository familyRepository;

    private WechatFamilyAccessGuard guard;

    @BeforeEach
    void setUp() {
        guard = new WechatFamilyAccessGuard(
                memberRepository,
                familyRepository);
    }

    @Test
    void roleChangeIsVisibleOnTheNextAuthorization() {
        FamilyMember adult = membership(FamilyRole.ADULT);
        FamilyMember child = membership(FamilyRole.CHILD);
        when(memberRepository.findByFamilyIdAndUserIdAndLeftAtIsNull(
                FAMILY_ID,
                USER_ID))
                .thenReturn(Optional.of(adult), Optional.of(child));
        when(familyRepository.findByIdAndDeletedAtIsNull(FAMILY_ID))
                .thenReturn(Optional.of(family()));

        var before = guard.requireAccess(USER_ID, FAMILY_ID, CONFIRM_TASK);
        var after = guard.requireAccess(USER_ID, FAMILY_ID, CONFIRM_TASK);

        assertThat(before.role()).isEqualTo(FamilyRole.ADULT);
        assertThat(after.role()).isEqualTo(FamilyRole.CHILD);
        assertThat(after.roleName()).isEqualTo("孩子成员");
    }

    @Test
    void leavingFamilyIsRejectedWithoutUsingStaleMembership() {
        when(memberRepository.findByFamilyIdAndUserIdAndLeftAtIsNull(
                FAMILY_ID,
                USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                guard.requireAccess(USER_ID, FAMILY_ID, READ_HISTORY))
                .isInstanceOf(WechatFamilyAccessDeniedException.class)
                .hasMessage("当前家庭不可用，请重新选择家庭");
    }

    @Test
    void deletedAndUnknownFamiliesUseTheSameNonLeakingError() {
        when(memberRepository.findByFamilyIdAndUserIdAndLeftAtIsNull(
                FAMILY_ID,
                USER_ID))
                .thenReturn(Optional.of(membership(FamilyRole.ADULT)));
        when(familyRepository.findByIdAndDeletedAtIsNull(FAMILY_ID))
                .thenReturn(Optional.empty());

        Throwable deleted = catchDenied(FAMILY_ID);

        when(memberRepository.findByFamilyIdAndUserIdAndLeftAtIsNull(
                999L,
                USER_ID))
                .thenReturn(Optional.empty());
        Throwable unknown = catchDenied(999L);

        assertThat(deleted).isInstanceOf(WechatFamilyAccessDeniedException.class);
        assertThat(unknown).isInstanceOf(WechatFamilyAccessDeniedException.class);
        assertThat(deleted.getMessage()).isEqualTo(unknown.getMessage());
    }

    private Throwable catchDenied(long familyId) {
        try {
            guard.requireAccess(USER_ID, familyId, READ_HISTORY);
            throw new AssertionError("应拒绝无权家庭");
        } catch (WechatFamilyAccessDeniedException ex) {
            return ex;
        }
    }

    private FamilyMember membership(FamilyRole role) {
        FamilyMember member = new FamilyMember();
        member.setFamilyId(FAMILY_ID);
        member.setUserId(USER_ID);
        member.setRole(role);
        member.setJoinedAt(Instant.parse("2026-10-01T00:00:00Z"));
        return member;
    }

    private Family family() {
        Family family = new Family();
        family.setId(FAMILY_ID);
        family.setName("一一家");
        family.setCreatedBy(USER_ID);
        family.setCreatedAt(Instant.parse("2026-10-01T00:00:00Z"));
        family.setUpdatedAt(Instant.parse("2026-10-01T00:00:00Z"));
        return family;
    }
}
