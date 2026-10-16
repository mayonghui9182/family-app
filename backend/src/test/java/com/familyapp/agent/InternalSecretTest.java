package com.familyapp.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 1（Change 2）：内部 Agent API 秘钥校验集成测试。
 * 覆盖 OpenSpec delta「内部 API 秘钥鉴权」三个场景：
 * 缺失秘钥 40101 / 错误秘钥 40102 / 正确秘钥放行 C-1。
 *
 * 说明：Profile 用 internal-api-test 而非 test —— D3 规定 test/internal-mock 直接放行，
 * 本测试必须走真实秘钥比较链路；正确秘钥经 @TestPropertySource 注入测试专属值。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("internal-api-test")
@TestPropertySource(properties = "internal-api.secret=test-secret-correct")
class InternalSecretTest {

    @Autowired MockMvc mvc;

    /** 用例1：缺 X-Internal-Secret 头 → HTTP 401 + code=40101「内部API秘钥缺失，请联系管理员」 */
    @Test
    void S1_missing_secret_rejected_40101() throws Exception {
        mvc.perform(get("/api/internal/agent/tasks"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101))
                .andExpect(jsonPath("$.msg").value("内部API秘钥缺失，请联系管理员"));
    }

    /** 用例2：头值错误「WrongSecret」→ HTTP 401 + code=40102「内部API秘钥不正确」 */
    @Test
    void S2_wrong_secret_rejected_40102() throws Exception {
        mvc.perform(get("/api/internal/agent/tasks").header("X-Internal-Secret", "WrongSecret"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40102))
                .andExpect(jsonPath("$.msg").value("内部API秘钥不正确"));
    }

    /** 用例3：正确秘钥「test-secret-correct」→ 放行，C-1 get_tasks 返回 200 + data=[] */
    @Test
    void S3_correct_secret_passes_get_tasks_returns_empty_list() throws Exception {
        mvc.perform(get("/api/internal/agent/tasks?family_id=99001&status=PENDING")
                .header("X-Internal-Secret", "test-secret-correct"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data", empty()));
    }
}
