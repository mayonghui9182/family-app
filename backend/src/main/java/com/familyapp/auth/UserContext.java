package com.familyapp.auth;

import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;

/**
 * 当前登录用户上下文。基于 ThreadLocal 在请求线程内传递当前用户身份。
 * <p>由 AuthInterceptor 在 preHandle 中填充，afterCompletion 中清理。</p>
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {}

    /**
     * 当前登录用户信息。userId 必填，familyId/role 在 AuthInterceptor 阶段尚不可知，
     * 后续 X-Family-Id 拦截器或 Controller 层会按需补齐。
     */
    public record CurrentUser(Long userId, String email, String nickname, Long familyId, String role) {}

    public static void setCurrent(CurrentUser user) {
        HOLDER.set(user);
    }

    /**
     * 获取当前登录用户。未登录抛 401 UNAUTHORIZED。
     */
    public static CurrentUser current() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw new ApiException(ResultCode.UNAUTHORIZED);
        }
        return user;
    }

    public static void clear() {
        HOLDER.remove();
    }
}