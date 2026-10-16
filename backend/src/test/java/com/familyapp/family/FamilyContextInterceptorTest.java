package com.familyapp.family;

import com.familyapp.auth.UserContext;
import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FamilyContextInterceptor 路径口径单元测试。
 *
 * <p>关键背景：{@code AuthInterceptor} 写入 UserContext 时 familyId 与 role 均为 null，
 * 二者只由本拦截器按 {@code X-Family-Id} 头补齐。因此新增业务前缀若不在白名单内，
 * 服务层的家庭隔离（硬约束 4）与 CHILD 只读判定（40302）都会静默失效。
 */
@ExtendWith(MockitoExtension.class)
class FamilyContextInterceptorTest {

    @Mock FamilyMemberRepository memberRepo;
    @InjectMocks FamilyContextInterceptor interceptor;

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    private static MockHttpServletRequest request(String uri, String familyHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        if (familyHeader != null) {
            request.addHeader("X-Family-Id", familyHeader);
        }
        return request;
    }

    /** AuthInterceptor 之后的上下文：有身份，但 familyId/role 待补 */
    private static void givenAuthenticatedUser() {
        UserContext.setCurrent(new UserContext.CurrentUser(100L, "a@b.com", "爷爷", null, null));
    }

    private static FamilyMember member(FamilyRole role) {
        FamilyMember m = new FamilyMember();
        m.setFamilyId(7L);
        m.setUserId(100L);
        m.setRole(role);
        return m;
    }

    @Test
    void calendar_path_resolves_family_and_role() {
        givenAuthenticatedUser();
        when(memberRepo.findByFamilyIdAndUserIdAndLeftAtIsNull(7L, 100L))
                .thenReturn(Optional.of(member(FamilyRole.CHILD)));

        boolean pass = interceptor.preHandle(request("/api/calendar/events", "7"),
                new MockHttpServletResponse(), new Object());

        assertEquals(true, pass);
        UserContext.CurrentUser curr = UserContext.current();
        assertEquals(7L, curr.familyId());
        assertEquals("CHILD", curr.role());
    }

    @Test
    void calendar_path_non_member_rejected_40301() {
        givenAuthenticatedUser();
        when(memberRepo.findByFamilyIdAndUserIdAndLeftAtIsNull(anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> interceptor.preHandle(
                request("/api/calendar/events/1", "7"), new MockHttpServletResponse(), new Object()));
        assertEquals(ResultCode.FAMILY_NOT_MEMBER, ex.getCode());
    }

    @Test
    void whitelisted_task_path_still_resolves_family_and_role() {
        givenAuthenticatedUser();
        when(memberRepo.findByFamilyIdAndUserIdAndLeftAtIsNull(7L, 100L))
                .thenReturn(Optional.of(member(FamilyRole.ADULT)));

        interceptor.preHandle(request("/api/tasks", "7"), new MockHttpServletResponse(), new Object());

        assertEquals(7L, UserContext.current().familyId());
        assertEquals("ADULT", UserContext.current().role());
    }

    @Test
    void non_whitelisted_path_is_skipped_without_repository_call() {
        givenAuthenticatedUser();
        HttpServletRequest request = request("/api/other-things", "7");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertEquals(null, UserContext.current().familyId());
        verify(memberRepo, never()).findByFamilyIdAndUserIdAndLeftAtIsNull(any(), any());
    }

    /**
     * 审查修复 M-1：兄弟前缀路径（如 /api/calendar-events）不得被 calendar 白名单误伤。
     *
     * <p>判别性所在：收紧前 {@code startsWith("/api/calendar")} 会命中该路径并解析成员；
     * 修复后要求「精确 /api/calendar 或 /api/calendar/ 前缀」，此请求应直接放行且完全不触库。
     */
    @Test
    void sibling_prefix_of_calendar_is_not_whitelisted() {
        givenAuthenticatedUser();

        interceptor.preHandle(request("/api/calendar-events", "7"),
                new MockHttpServletResponse(), new Object());

        assertEquals(null, UserContext.current().familyId());
        verify(memberRepo, never()).findByFamilyIdAndUserIdAndLeftAtIsNull(any(), any());
    }

    /** 审查修复 M-1 的防回归：收紧为双条件后，精确根路径 /api/calendar 仍须命中白名单 */
    @Test
    void exact_calendar_root_path_resolves_family_context() {
        givenAuthenticatedUser();
        when(memberRepo.findByFamilyIdAndUserIdAndLeftAtIsNull(7L, 100L))
                .thenReturn(Optional.of(member(FamilyRole.ADULT)));

        interceptor.preHandle(request("/api/calendar", "7"),
                new MockHttpServletResponse(), new Object());

        assertEquals(7L, UserContext.current().familyId());
        assertEquals("ADULT", UserContext.current().role());
    }
}
