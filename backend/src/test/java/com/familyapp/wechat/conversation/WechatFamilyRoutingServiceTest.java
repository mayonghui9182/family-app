package com.familyapp.wechat.conversation;

import com.familyapp.family.Family;
import com.familyapp.family.FamilyMember;
import com.familyapp.family.FamilyMemberRepository;
import com.familyapp.family.FamilyRepository;
import com.familyapp.family.FamilyRole;
import com.familyapp.user.User;
import com.familyapp.wechat.WechatBind;
import com.familyapp.wechat.WechatBindRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.familyapp.wechat.conversation.WechatFamilyRoutingService.RouteOutcome.AMBIGUOUS;
import static com.familyapp.wechat.conversation.WechatFamilyRoutingService.RouteOutcome.AUTO_SELECTED;
import static com.familyapp.wechat.conversation.WechatFamilyRoutingService.RouteOutcome.CHALLENGE;
import static com.familyapp.wechat.conversation.WechatFamilyRoutingService.RouteOutcome.EXPIRED;
import static com.familyapp.wechat.conversation.WechatFamilyRoutingService.RouteOutcome.NO_FAMILY;
import static com.familyapp.wechat.conversation.WechatFamilyRoutingService.RouteOutcome.NO_MATCH;
import static com.familyapp.wechat.conversation.WechatFamilyRoutingService.RouteOutcome.SELECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WechatFamilyRoutingServiceTest {

    private static final long BIND_ID = 71L;
    private static final long USER_ID = 81L;
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");

    @Mock
    private WechatBindRepository bindRepository;
    @Mock
    private FamilyMemberRepository memberRepository;
    @Mock
    private FamilyRepository familyRepository;
    @Mock
    private WechatFamilyAccessGuard accessGuard;

    private WechatFamilyRoutingService service;

    @BeforeEach
    void setUp() {
        service = new WechatFamilyRoutingService(
                bindRepository,
                memberRepository,
                familyRepository,
                accessGuard);
        when(bindRepository.findById(BIND_ID)).thenReturn(Optional.of(bind()));
    }

    @Test
    void singleFamilyIsAutoSelectedWithCorrectRoleName() {
        useFamilies(
                List.of(member(1L, FamilyRole.OWNER)),
                List.of(family(1L, "一一家")));

        var decision = service.beginSelection(BIND_ID, 4L, NOW);

        assertThat(decision.outcome()).isEqualTo(AUTO_SELECTED);
        assertThat(decision.selected().familyId()).isEqualTo(1L);
        assertThat(decision.selected().role()).isEqualTo(FamilyRole.OWNER);
        assertThat(decision.selected().roleName()).isEqualTo("家庭管理员");
        assertThat(decision.challenge()).isNull();
    }

    @Test
    void automaticRouteUsesCurrentThenDefaultThenStableIdRegardlessOfNameOrder() {
        useFamilies(List.of(member(1L, FamilyRole.OWNER), member(2L, FamilyRole.ADULT)),
                List.of(family(2L, "A家"), family(1L, "Z家")));
        assertThat(service.automaticSelection(BIND_ID, null, null).selected().familyId()).isEqualTo(1L);
        assertThat(service.automaticSelection(BIND_ID, null, 2L).selected().familyId()).isEqualTo(2L);
        assertThat(service.automaticSelection(BIND_ID, 1L, 2L).selected().familyId()).isEqualTo(1L);
        assertThat(service.automaticSelection(BIND_ID, 999L, 999L).selected().familyId()).isEqualTo(1L);
        assertThat(service.uniqueFamily(BIND_ID, "外部家庭")).isEmpty();
    }

    @Test
    void removedDefaultIsNotSelectedAndAmbiguousDefaultNameIsRejected() {
        useFamilies(List.of(member(1L, FamilyRole.OWNER), member(2L, FamilyRole.ADULT)),
                List.of(family(1L, "同名家"), family(2L, "同名家")));
        assertThat(service.uniqueFamily(BIND_ID, "同名家")).isEmpty();
        doThrow(new WechatFamilyAccessDeniedException()).when(accessGuard).requireAccess(
                USER_ID, 2L, WechatFamilyAccessGuard.AccessOperation.SELECT_FAMILY);
        assertThat(service.automaticSelection(BIND_ID, null, 2L).selected().familyId()).isEqualTo(1L);
    }

    @Test
    void multipleFamiliesSupportSnapshotNumberAndUniqueNormalizedName() {
        List<FamilyMember> members = List.of(
                member(1L, FamilyRole.ADULT),
                member(2L, FamilyRole.CHILD));
        List<Family> families = List.of(
                family(1L, "A 家"),
                family(2L, "B家"));
        useFamilies(members, families);
        when(memberRepository.findAllByUserIdAndLeftAtIsNull(USER_ID))
                .thenReturn(members);
        when(familyRepository.findByIdInAndDeletedAtIsNull(any()))
                .thenReturn(families);

        var begin = service.beginSelection(BIND_ID, 4L, NOW);

        assertThat(begin.outcome()).isEqualTo(CHALLENGE);
        assertThat(begin.challenge().candidateFamilyIds()).containsExactly(1L, 2L);
        assertThat(begin.options())
                .extracting(WechatFamilyRoutingService.FamilyOption::roleName)
                .containsExactly("成人成员", "孩子成员");

        var byNumber = service.resolveSelection(
                BIND_ID,
                4L,
                begin.challenge(),
                "第2个",
                NOW.plusSeconds(10));
        assertThat(byNumber.outcome()).isEqualTo(SELECTED);
        assertThat(byNumber.selected().familyId()).isEqualTo(2L);

        var byName = service.resolveSelection(
                BIND_ID,
                4L,
                begin.challenge(),
                "切换到 Ａ 家",
                NOW.plusSeconds(20));
        assertThat(byName.outcome()).isEqualTo(SELECTED);
        assertThat(byName.selected().familyId()).isEqualTo(1L);
    }

    @Test
    void ambiguousNormalizedNameCreatesNewChallengeWithoutSelecting() {
        useFamilies(
                List.of(
                        member(1L, FamilyRole.ADULT),
                        member(2L, FamilyRole.ADULT)),
                List.of(
                        family(1L, "幸福家"),
                        family(2L, "幸 福 家")));

        var begin = service.beginSelection(BIND_ID, 9L, NOW);
        var decision = service.resolveSelection(
                BIND_ID,
                9L,
                begin.challenge(),
                "切换到幸福家",
                NOW.plusSeconds(30));

        assertThat(decision.outcome()).isEqualTo(AMBIGUOUS);
        assertThat(decision.selected()).isNull();
        assertThat(decision.options()).hasSize(2);
        assertThat(decision.challenge().candidateFamilyIds()).containsExactly(1L, 2L);
    }

    @Test
    void expiredOrRevisionMismatchedNumberCannotSelect() {
        useFamilies(
                List.of(
                        member(1L, FamilyRole.ADULT),
                        member(2L, FamilyRole.ADULT)),
                List.of(
                        family(1L, "A家"),
                        family(2L, "B家")));

        var begin = service.beginSelection(BIND_ID, 3L, NOW);

        var expired = service.resolveSelection(
                BIND_ID,
                3L,
                begin.challenge(),
                "1",
                begin.challenge().expiresAt());
        var staleRevision = service.resolveSelection(
                BIND_ID,
                4L,
                begin.challenge(),
                "1",
                NOW.plusSeconds(1));

        assertThat(expired.outcome()).isEqualTo(EXPIRED);
        assertThat(staleRevision.outcome()).isEqualTo(EXPIRED);
        assertThat(expired.selected()).isNull();
        assertThat(staleRevision.selected()).isNull();
    }

    @Test
    void removedMembershipInvalidatesSnapshotNumberImmediately() {
        List<FamilyMember> initialMembers = List.of(
                member(1L, FamilyRole.ADULT),
                member(2L, FamilyRole.ADULT));
        List<Family> initialFamilies = List.of(
                family(1L, "A家"),
                family(2L, "B家"));
        List<FamilyMember> currentMembers =
                List.of(member(2L, FamilyRole.ADULT));
        List<Family> currentFamilies = List.of(family(2L, "B家"));

        when(memberRepository.findAllByUserIdAndLeftAtIsNull(USER_ID))
                .thenReturn(initialMembers, currentMembers);
        when(familyRepository.findByIdInAndDeletedAtIsNull(any()))
                .thenReturn(initialFamilies, currentFamilies);

        var begin = service.beginSelection(BIND_ID, 6L, NOW);
        var decision = service.resolveSelection(
                BIND_ID,
                6L,
                begin.challenge(),
                "1",
                NOW.plusSeconds(15));

        assertThat(decision.outcome()).isEqualTo(NO_MATCH);
        assertThat(decision.selected()).isNull();
        assertThat(decision.options())
                .extracting(WechatFamilyRoutingService.FamilyOption::familyId)
                .containsExactly(2L);
    }

    @Test
    void finalSelectionGuardFailureDoesNotLeakFamily() {
        useFamilies(
                List.of(member(1L, FamilyRole.ADULT)),
                List.of(family(1L, "已撤权家庭")));
        doThrow(new WechatFamilyAccessDeniedException())
                .when(accessGuard)
                .requireAccess(
                        USER_ID,
                        1L,
                        WechatFamilyAccessGuard.AccessOperation.SELECT_FAMILY);

        var decision = service.beginSelection(BIND_ID, 1L, NOW);

        assertThat(decision.outcome()).isEqualTo(NO_FAMILY);
        assertThat(decision.options()).isEmpty();
        assertThat(decision.selected()).isNull();
    }

    @Test
    void repositoryOverReturnCannotLeakNonMemberOrDeletedFamily() {
        Family deleted = family(2L, "已删除家庭");
        deleted.setDeletedAt(NOW);
        useFamilies(
                List.of(member(1L, FamilyRole.ADULT)),
                List.of(
                        family(1L, "我的家"),
                        deleted,
                        family(999L, "别人的家")));

        var decision = service.beginSelection(BIND_ID, 1L, NOW);

        assertThat(decision.outcome()).isEqualTo(AUTO_SELECTED);
        assertThat(decision.options())
                .extracting(WechatFamilyRoutingService.FamilyOption::familyId)
                .containsExactly(1L);
        assertThat(decision.options())
                .extracting(WechatFamilyRoutingService.FamilyOption::name)
                .doesNotContain("已删除家庭", "别人的家");
    }

    private void useFamilies(List<FamilyMember> members, List<Family> families) {
        when(memberRepository.findAllByUserIdAndLeftAtIsNull(USER_ID))
                .thenReturn(members);
        when(familyRepository.findByIdInAndDeletedAtIsNull(any()))
                .thenReturn(families);
    }

    private WechatBind bind() {
        User user = new User();
        user.setId(USER_ID);
        WechatBind bind = new WechatBind();
        bind.setOpenid("o-routing-user");
        bind.setUser(user);
        return bind;
    }

    private FamilyMember member(long familyId, FamilyRole role) {
        FamilyMember member = new FamilyMember();
        member.setFamilyId(familyId);
        member.setUserId(USER_ID);
        member.setRole(role);
        member.setJoinedAt(NOW.minusSeconds(3600));
        return member;
    }

    private Family family(long familyId, String name) {
        Family family = new Family();
        family.setId(familyId);
        family.setName(name);
        family.setCreatedBy(USER_ID);
        family.setCreatedAt(NOW.minusSeconds(3600));
        family.setUpdatedAt(NOW.minusSeconds(3600));
        return family;
    }
}
