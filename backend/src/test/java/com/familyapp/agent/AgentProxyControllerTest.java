package com.familyapp.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Change 6 Task 1 Step 9：AgentProxyController 10 端点测试。
 *
 * RED→GREEN 进度：
 *   B2c 7 用例（C27/C28/C32×2/C33/C34/PUBLIC）→ GREEN 7/7 ✅
 *   C29/C30/C31 3 用例 → RED 待验证（本轮追加）
 *
 * ⚠️ @Transactional：claim 用 REQUIRES_NEW 内层事务 COMMIT 后外层 rollback 不生效。
 *   FamilyControllerIntegrationTest 加了 @DirtiesContext(BEFORE_CLASS) 处理脏数据。
 *   AgentProxy 自己不加 DirtiesContext（否则 Jsonb*Test 空库 42104）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AgentProxyControllerTest {

    static final String API = "/api/agent/v1";

    @Autowired MockMvc mvc;

    // ==================== C-27 模板列表（家庭隔离）====================

    @Test
    void C27_GET_templates_returnsCode0_andDataArray() throws Exception {
        mvc.perform(get(API + "/templates"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data").isArray());
    }

    // ==================== C-28 模板版本历史 ====================

    @Test
    void C28_GET_templateVersions_returnsCode0_andVersionsList() throws Exception {
        mvc.perform(get(API + "/templates/9999/versions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.template_id").value(9999))
            .andExpect(jsonPath("$.data.versions").isArray());
    }

    // ==================== C-32 手动触发总结（CHILD 必须 403）====================

    @Test
    void C32_CHILD_triggerSummary_returns40302_chineseMessage() throws Exception {
        mvc.perform(get(API + "/summary/trigger/1")
                .header("X-Test-Role", "CHILD"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(40302))
            .andExpect(jsonPath("$.msg", notNullValue()))
            .andExpect(jsonPath("$.msg", containsString("小孩")))
            .andExpect(jsonPath("$.msg", containsString("总结")));
    }

    @Test
    void C32_OWNER_triggerSummary_returnsCode0_orSummaryAlreadyRan() throws Exception {
        String content = mvc.perform(get(API + "/summary/trigger/1")
                .header("X-Test-Role", "OWNER"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(
            content.contains("\"code\":0") || content.contains("\"code\":46502"),
            "OWNER 触发总结应返回 0 或 46502，实际：" + content);
    }

    // ==================== C-33 总结日志列表 ====================

    @Test
    void C33_GET_summaryList_returnsCode0_andDataArray() throws Exception {
        mvc.perform(get(API + "/summary/list/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data").isArray());
    }

    // ==================== C-34 总结 3 指标聚合 ====================

    @Test
    void C34_GET_summaryMetrics_returnsCode0_andThreeMetrics() throws Exception {
        mvc.perform(get(API + "/summary/metrics/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.success_rate_30d").exists())
            .andExpect(jsonPath("$.data.version_count_30d").exists())
            .andExpect(jsonPath("$.data.learned_adjustments_30d").exists());
    }

    // ==================== C-29 PATCH star toggle（RED）====================

    @Test
    void C29_PATCH_star_versionNotFound_returns40404() throws Exception {
        mvc.perform(patch(API + "/templates/versions/99999/star"))
            .andExpect(jsonPath("$.code").value(40404));
    }

    // ==================== C-30 DELETE version（RED）====================

    @Test
    void C30_DELETE_version_notFound_returns40404() throws Exception {
        mvc.perform(delete(API + "/templates/versions/99999"))
            .andExpect(jsonPath("$.code").value(40404));
    }

    // ==================== C-31 POST pick（RED）====================

    @Test
    void C31_POST_pick_emptyBody_returns40099() throws Exception {
        mvc.perform(post(API + "/templates/1/versions/1/pick")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(jsonPath("$.code").value(40099));
    }

    // ==================== 拦截器零改动验证 ====================

    @Test
    void PUBLIC_agentApi_doesNotRequireInternalSecretHeader() throws Exception {
        int status = mvc.perform(get(API + "/templates"))
            .andReturn().getResponse().getStatus();
        org.junit.jupiter.api.Assertions.assertNotEquals(403, status,
            "/api/agent/v1/** 不应该要求 X-Internal-Secret 头（403）");
    }
}