package com.familyapp.auth;

import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.user.User;
import com.familyapp.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Duration TOKEN_TTL = Duration.ofDays(7);
    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");

    private final UserRepository userRepository;

    public AuthInterceptor(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return true;
        }

        String token = request.getHeader("X-Access-Token");
        boolean tokenPresent = token != null && !token.isBlank();
        UserContext.CurrentUser user = null;

        if (tokenPresent) {
            if (!HEX64.matcher(token).matches()) {
                if (!isPublicPath(path)) throw new ApiException(ResultCode.AUTH_HEADER_REQUIRED);
            } else {
                Optional<User> opt = userRepository.findByAccessToken(token)
                        .filter(u -> u.getDeletedAt() == null);
                if (opt.isPresent()) {
                    User u = opt.get();
                    Instant updatedAt = u.getTokenUpdatedAt();
                    boolean expired = updatedAt == null || Instant.now().isAfter(updatedAt.plus(TOKEN_TTL));
                    if (!expired) {
                        user = new UserContext.CurrentUser(
                                u.getId(), u.getEmail(), u.getNickname(), null, null
                        );
                    } else if (!isPublicPath(path)) {
                        u.setAccessToken(null);
                        u.setTokenUpdatedAt(null);
                        userRepository.save(u);
                        throw new ApiException(ResultCode.AUTH_TOKEN_EXPIRED);
                    }
                } else if (!isPublicPath(path)) {
                    throw new ApiException(ResultCode.AUTH_TOKEN_INVALID);
                }
            }
        }

        if (!isPublicPath(path)) {
            if (!tokenPresent) throw new ApiException(ResultCode.AUTH_HEADER_REQUIRED);
            if (user == null) throw new ApiException(ResultCode.AUTH_TOKEN_INVALID);
        }

        if (user != null) UserContext.setCurrent(user);
        return true;
    }

    private boolean isPublicPath(String path) {
        return java.util.Set.of("/api/auth/login", "/api/auth/register", "/api/auth/wechat-bind",
                "/api/auth/password-reset/request", "/api/auth/password-reset/confirm").contains(path);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        UserContext.clear();
    }
}
