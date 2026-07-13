package com.family.common.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.family.common.context.UserContext;
import com.family.common.result.Result;
import com.family.common.result.ResultCode;
import com.family.config.JwtConfig;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtConfig jwtConfig;
    private final ObjectMapper objectMapper;

    /**
     * 预处理回调方法，在控制器方法执行之前调用
     *
     * @param request  请求
     * @param response 响应
     * @param handler  处理器
     * @return 是否继续执行
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // OPTIONS 请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 从请求头获取 token
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeUnauthorizedResponse(response, ResultCode.UNAUTHORIZED);
            return false;
        }

        String token = authHeader.substring(7);
        try {
            // 验证 token
            if (!jwtConfig.validateToken(token)) {
                writeUnauthorizedResponse(response, ResultCode.TOKEN_INVALID);
                return false;
            }

            // 解析 token 并设置用户上下文
            Claims claims = jwtConfig.parseToken(token);
            Long userId = Long.valueOf(claims.getSubject());
            Long familyId = claims.get("familyId", Long.class);
            String role = claims.get("role", String.class);

            UserContext.setUserId(userId);
            UserContext.setFamilyId(familyId);
            UserContext.setRole(role);

            return true;
        } catch (Exception e) {
            log.warn("Token 解析失败：{}", e.getMessage());
            writeUnauthorizedResponse(response, ResultCode.TOKEN_INVALID);
            return false;
        }
    }

    /**
     * 完成回调方法，在视图渲染完成后调用
     *
     * @param request  请求
     * @param response 响应
     * @param handler  处理器
     * @param ex       异常
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 清除用户上下文
        UserContext.clear();
    }

    /**
     * 写入未授权响应
     *
     * @param response   响应
     * @param resultCode 响应码
     */
    private void writeUnauthorizedResponse(HttpServletResponse response, ResultCode resultCode) {
        try {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            Result<Void> result = Result.error(resultCode);
            response.getWriter().write(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            log.error("写入未授权响应失败：", e);
        }
    }

}
