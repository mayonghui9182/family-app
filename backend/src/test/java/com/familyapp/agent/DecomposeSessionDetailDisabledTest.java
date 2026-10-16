package com.familyapp.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Change 14 Task 11 · D-8 Step1-⑤：AI 总开关关闭时草稿详情读取被守卫拦截。
 *
 * <p>@TestPropertySource 为类级注解，无法与 DecomposeSessionDetailTest（开关开启态）共存，故独立成类。
 * 断言守卫在触库之前生效：草稿 id 根本不存在，仍返回 50310 而非 46001。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "familyapp.ai-decompose.enabled=false")
class DecomposeSessionDetailDisabledTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("D8-⑤ 开关关闭 → 503 + 50310，守卫先于任何 DB 访问（id 不存在也报开关错）")
    void d85_AI开关关闭_返回50310() throws Exception {
        mvc.perform(get("/api/agent/v1/decompose/sessions/1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50310))
                .andExpect(jsonPath("$.msg").value(containsString("AI 拆解功能已关闭")));
    }
}
