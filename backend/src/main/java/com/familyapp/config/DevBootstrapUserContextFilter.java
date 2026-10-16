package com.familyapp.config;

import com.familyapp.auth.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 【S4 Step4 R1-b 最小修复】bootRun main classpath 专用（test profile）的 UserContext 初始化过滤器。
 *
 * 背景：
 *   · src/test/java/.../TestUserContextFilter.java 只在 ./gradlew test 的 test classpath 存在
 *   · bootRun（main classpath）即使 SPRING_PROFILES_ACTIVE=test，也**不会加载 src/test 的 classes**
 *     → 导致全局 UserContext 永远 null → 服务层 UserContext.getCurrentFamilyId() NPE → code=50000
 *   · 本类在 src/main（main runtime classpath），通过 @Profile("test") 仅当 test 激活时注册 bean，prod 永不激活
 *
 * 角色可通过请求头切换：X-Test-Role: OWNER / MEMBER / CHILD；默认 OWNER，默认 familyId=1，userId=1
 */
@Component
@Profile("test")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DevBootstrapUserContextFilter extends OncePerRequestFilter {

    public static final Long DEFAULT_USER_ID = 1L;
    public static final String DEFAULT_EMAIL = "test@test.com";
    public static final String DEFAULT_NICK = "test";
    public static final Long DEFAULT_FAMILY_ID = 1L;
    public static final String DEFAULT_ROLE = "OWNER";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Long userId = parseLongHeader(request, "X-Test-User-Id", DEFAULT_USER_ID);
            String email = firstNonNull(request.getHeader("X-Test-Email"), DEFAULT_EMAIL);
            String nick  = firstNonNull(request.getHeader("X-Test-Nick"),  DEFAULT_NICK);
            Long familyId = parseLongHeader(request, "X-Test-Family-Id", DEFAULT_FAMILY_ID);
            String role   = firstNonNull(request.getHeader("X-Test-Role"), DEFAULT_ROLE);
            UserContext.setCurrent(new UserContext.CurrentUser(userId, email, nick, familyId, role));
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
    private static Long parseLongHeader(HttpServletRequest req, String name, Long defaultValue) {
        String v = req.getHeader(name);
        if (v != null && !v.isBlank()) try { return Long.parseLong(v); } catch (Exception ignore) {}
        return defaultValue;
    }
    @SuppressWarnings("unused")
    private static <T> T firstNonNull(T a, T b) { return a != null ? a : b; }
}
