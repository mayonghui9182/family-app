package com.familyapp.config;

import com.familyapp.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部 Agent API 秘钥拦截器（Change 2 Task 1）。
 *
 * <p>守护 /api/internal/**：请求必须携带 X-Internal-Secret 头，服务端用
 * MessageDigest.isEqual 做恒定时间比较，避免逐字节短路造成时序侧信道泄露。</p>
 *
 * <p>错误响应按 OpenSpec delta 规范直接写 JSON Result 信封（HTTP 401，
 * Content-Type application/json;charset=UTF-8）：缺失 → code=40101「内部API秘钥缺失，请联系管理员」；
 * 不匹配 → code=40102「内部API秘钥不正确」。注意：40101/40102 数字上与用户登录态错误码重叠是
 * delta 规范的既定决策 —— 内部 API 响应只被 agent-service 消费、不会回传前端，因此此处不经
 * ResultCode 枚举（枚举内同码不同义会造成反查歧义），直接构造 Result 写出。</p>
 *
 * <p>Profile 放行（design.md D3）：当前激活 Profile 含 test 或 internal-mock 时跳过秘钥校验
 * 直接 return true，供自动化测试与 agent-service 联调 mock 使用。</p>
 */
@Component
public class InternalSecretInterceptor implements HandlerInterceptor {

    /** 缺失秘钥业务码（OpenSpec delta 既定值；与 AUTH_TOKEN_INVALID 数字重叠为规范决策）。 */
    private static final int CODE_SECRET_MISSING = 40101;
    /** 秘钥不匹配业务码（OpenSpec delta 既定值；与 AUTH_TOKEN_EXPIRED 数字重叠为规范决策）。 */
    private static final int CODE_SECRET_INVALID = 40102;

    /** 内部 API 秘钥请求头名称。 */
    public static final String HEADER_NAME = "X-Internal-Secret";

    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final String expectedSecret;

    public InternalSecretInterceptor(Environment environment,
                                     ObjectMapper objectMapper,
                                     @Value("${internal-api.secret}") String expectedSecret) {
        this.environment = environment;
        this.objectMapper = objectMapper;
        if (expectedSecret == null || expectedSecret.isBlank()) {
            throw new IllegalArgumentException("INTERNAL_API_SECRET 不能为空");
        }
        this.expectedSecret = expectedSecret;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // design.md D3：test / internal-mock Profile 下跳过秘钥校验，直接放行。
        if (isBypassProfile()) {
            return true;
        }

        String header = request.getHeader(HEADER_NAME);
        if (header == null || header.isBlank()) {
            // 空头（未携带或空白）→ 40101「内部API秘钥缺失」。
            writeError(response, CODE_SECRET_MISSING, "内部API秘钥缺失，请联系管理员");
            return false;
        }
        // 恒定时间比较，防止时序攻击逐字节猜测秘钥。
        boolean matched = MessageDigest.isEqual(
                header.getBytes(StandardCharsets.UTF_8),
                expectedSecret.getBytes(StandardCharsets.UTF_8));
        if (!matched) {
            // 错头 → 40102「内部API秘钥不正确」。
            writeError(response, CODE_SECRET_INVALID, "内部API秘钥不正确");
            return false;
        }
        return true;
    }

    /** 判断当前激活 Profile 是否命中放行名单（test / internal-mock）。 */
    private boolean isBypassProfile() {
        for (String active : environment.getActiveProfiles()) {
            if ("test".equals(active) || "internal-mock".equals(active)) {
                return true;
            }
        }
        return false;
    }

    /** 直接把 Result 信封以 JSON 写回客户端（HTTP 401），不走全局异常处理器。 */
    private void writeError(HttpServletResponse response, int code, String msg) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(new Result<>(code, msg, null)));
        response.getWriter().flush();
    }
}
