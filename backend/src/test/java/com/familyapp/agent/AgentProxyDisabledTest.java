package com.familyapp.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Change 7 Task 2 子步骤 8：AI 拆解功能开关关闭时的守卫测试。
 * <p>使用默认 test Profile（application-test.yml：familyapp.auth.enabled=false，
 * WebConfig 拦截器不加载，/api/agent/v1/** 直接可达，无需 X-Access-Token/X-Family-Id）。
 * @TestPropertySource 覆盖 familyapp.ai-decompose.enabled=false，触发 AgentProxyController 守卫。
 * <p>断言：C-25 / C-32 入口返回 50310 + 中文消息；D-7 /config 不受守卫、返回开关状态 false。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "familyapp.ai-decompose.enabled=false")
class AgentProxyDisabledTest {

    @Autowired
    MockMvc mvc;

    // C-25：模板列表 -> 守卫拦截，HTTP 503 + code=50310 + 中文消息
    @Test
    void c25_listTemplates_returns50310_whenDisabled() throws Exception {
        mvc.perform(get("/api/agent/v1/templates"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50310))
                .andExpect(jsonPath("$.msg").value(containsString("AI 拆解功能已关闭")));
    }

    // C-32：手动触发总结 -> 守卫拦截，HTTP 503 + code=50310 + 中文消息
    @Test
    void c32_triggerSummary_returns50310_whenDisabled() throws Exception {
        mvc.perform(post("/api/agent/v1/summary/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50310))
                .andExpect(jsonPath("$.msg").value(containsString("AI 拆解功能已关闭")));
    }

    // D-7：/config 不受守卫，返回开关状态 false（前端 stores/decompose.js 依赖此契约）
    @Test
    void d7_config_returnsEnabledFalse_whenDisabled() throws Exception {
        mvc.perform(get("/api/agent/v1/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ai_decompose_enabled").value(false));
    }
}
