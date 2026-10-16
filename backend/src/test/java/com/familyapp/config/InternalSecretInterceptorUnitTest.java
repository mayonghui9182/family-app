package com.familyapp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InternalSecretInterceptor 纯单元测试（不启动 Spring 容器）。
 *
 * <p>覆盖 design.md D3 的 Profile 放行分支与秘钥缺失拒绝分支：
 * 防止放行名单（test / internal-mock）被改名或删改后静默失效
 * （评审 finding [D3-profile-bypass-no-test]）。集成链路见 InternalSecretTest。</p>
 */
class InternalSecretInterceptorUnitTest {

    private static final String SECRET = "test-secret-correct";

    private MockEnvironment environment;
    private InternalSecretInterceptor interceptor;

    @Test
    void blankInternalSecretFailsAtStartupInsteadOfAcceptingBrokenProductionConfig() {
        var production = new MockEnvironment();
        production.setActiveProfiles("prod");
        for (String secret : new String[]{null, "", "   "}) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new InternalSecretInterceptor(production, new ObjectMapper(), secret));
        }
    }

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment();
        interceptor = new InternalSecretInterceptor(environment, new ObjectMapper(), SECRET);
    }

    /** 正例：激活 Profile 含 internal-mock（D3 放行名单）→ 不带任何请求头也直接放行 return true。 */
    @Test
    void U1_internalMock_profile_bypasses_without_header() throws Exception {
        environment.setActiveProfiles("internal-mock");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/internal/agent/tasks");
        // 刻意不设置 X-Internal-Secret 头 —— 放行判定必须发生在读头之前（design.md D3）
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean passed = interceptor.preHandle(request, response, new Object());

        assertTrue(passed, "internal-mock Profile 下应跳过秘钥校验直接放行");
        assertTrue(response.getContentAsString().isEmpty(), "放行路径不应写任何响应体");
    }

    /** 负例：激活 Profile 不在放行名单且缺头 → return false，响应 HTTP 401 + code=40101。 */
    @Test
    void U2_no_bypass_profile_missing_header_rejected_40101() throws Exception {
        environment.setActiveProfiles("production"); // 非 test / internal-mock
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/internal/agent/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean passed = interceptor.preHandle(request, response, new Object());

        assertFalse(passed, "非放行 Profile 且缺秘钥头应被拒绝");
        assertEquals(401, response.getStatus());
        assertEquals("application/json;charset=UTF-8", response.getContentType());
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":40101"), "响应体应含业务码 40101：" + body);
        assertTrue(body.contains("内部API秘钥缺失，请联系管理员"), "响应体应含中文提示：" + body);
    }
}
