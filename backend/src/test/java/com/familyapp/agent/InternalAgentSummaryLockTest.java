package com.familyapp.agent;

import com.familyapp.family.Family;
import com.familyapp.family.FamilyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Change 5 Task 0：C-19 抢锁 / C-20 收尾 / C-21 change-summary 追加 / C-22 版本清理。
 * H2 无表达式唯一索引 → 锁冲突路径由 SummaryClaimConflictTest（@MockBean）覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("internal-api-test")
@TestPropertySource(properties = "internal-api.secret=test-secret-correct")
@Transactional
class InternalAgentSummaryLockTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET = "test-secret-correct";
    private static final Long FID = 9501L;

    @Autowired MockMvc mvc;
    @Autowired SummaryExecutionLogRepository summaryLogRepository;
    @Autowired RuleTemplateRepository templateRepository;
    @Autowired RuleTemplateVersionRepository versionRepository;
    @Autowired FamilyRepository familyRepository;

    private RuleTemplate seedTemplate(String name) {
        try {
            RuleTemplate t = new RuleTemplate();
            t.setFamilyId(FID);
            t.setType("decomposition");
            t.setName(name);
            t.setContent(MAPPER.readTree("{\"title\":\"" + name + "\"}"));
            t.setIsActive(true);
            return templateRepository.save(t);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private RuleTemplateVersion seedVersion(RuleTemplate t, int version, String changeSummary,
                                            boolean important, Instant createdAt) {
        try {
            RuleTemplateVersion v = new RuleTemplateVersion();
            v.setFamilyId(FID);
            v.setTemplateId(t.getId());
            v.setVersion(version);
            v.setContent(MAPPER.readTree("{}"));
            v.setChangeSummary(changeSummary);
            v.setIsImportant(important);
            v.setCreatedAt(createdAt);
            return versionRepository.save(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== C-19 抢锁 ====================

    @Test
    void C19_claim_success_returnsAcquiredTrueWithRunningRow() throws Exception {
        mvc.perform(post("/api/internal/agent/summary-log/claim?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json")
                        .content("{\"trigger_source\":\"auto\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.acquired").value(true))
                .andExpect(jsonPath("$.data.log_id").isNumber());

        List<SummaryExecutionLog> rows = summaryLogRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo("running");
        assertThat(rows.get(0).getTriggerSource()).isEqualTo("auto");
    }

    @Test
    void C19_claim_force_deletesSameDayThenReclaims() throws Exception {
        mvc.perform(post("/api/internal/agent/summary-log/claim?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(jsonPath("$.data.acquired").value(true));

        mvc.perform(post("/api/internal/agent/summary-log/claim?family_id=" + FID + "&force=true")
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json")
                        .content("{\"trigger_source\":\"manual\"}"))
                .andExpect(jsonPath("$.data.acquired").value(true));

        Instant[] window = SummaryLockService.utcDayWindow();
        List<SummaryExecutionLog> sameDay = summaryLogRepository
                .findByFamilyIdAndRunAtGreaterThanEqualAndRunAtLessThan(FID, window[0], window[1]);
        assertThat(sameDay).hasSize(1);
        assertThat(sameDay.get(0).getTriggerSource()).isEqualTo("manual");
    }

    @Test
    void C19_claim_badTriggerSource_40099() throws Exception {
        mvc.perform(post("/api/internal/agent/summary-log/claim?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json")
                        .content("{\"trigger_source\":\"hacker\"}"))
                .andExpect(jsonPath("$.code").value(40099));
    }

    // ==================== C-20 收尾 ====================

    @Test
    void C20_finish_success_updatesStatusAndCategories() throws Exception {
        SummaryExecutionLog row = new SummaryExecutionLog();
        row.setFamilyId(FID);
        row.setStatus("running");
        row.setTriggerSource("auto");
        row = summaryLogRepository.saveAndFlush(row);

        mvc.perform(put("/api/internal/agent/summary-log/" + row.getId() + "?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json")
                        .content("{\"status\":\"success\",\"categories_updated\":[1,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        SummaryExecutionLog reloaded = summaryLogRepository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("success");
        assertThat(reloaded.getCategoriesUpdated().size()).isEqualTo(2);
    }

    @Test
    void C20_finish_wrongFamily_40407() throws Exception {
        SummaryExecutionLog row = new SummaryExecutionLog();
        row.setFamilyId(FID);
        row.setStatus("running");
        row = summaryLogRepository.saveAndFlush(row);

        mvc.perform(put("/api/internal/agent/summary-log/" + row.getId() + "?family_id=999999")
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json")
                        .content("{\"status\":\"success\"}"))
                .andExpect(jsonPath("$.code").value(40407));
    }

    @Test
    void C20_finish_twice_40011() throws Exception {
        SummaryExecutionLog row = new SummaryExecutionLog();
        row.setFamilyId(FID);
        row.setStatus("running");
        row = summaryLogRepository.saveAndFlush(row);

        String body = "{\"status\":\"failed\",\"failed_categories\":[{\"category_id\":7,\"reason\":\"单分类超时\"}]}";
        mvc.perform(put("/api/internal/agent/summary-log/" + row.getId() + "?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json").content(body))
                .andExpect(jsonPath("$.code").value(0));

        mvc.perform(put("/api/internal/agent/summary-log/" + row.getId() + "?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json").content(body))
                .andExpect(jsonPath("$.code").value(40011));

        SummaryExecutionLog reloaded = summaryLogRepository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getErrorMessage()).contains("分类 7：单分类超时");
    }

    // ==================== C-21 change-summary 追加 ====================

    @Test
    void C21_append_ok_concatsWithNewline() throws Exception {
        RuleTemplate t = seedTemplate("家务分解");
        seedVersion(t, 2, "【2026-08-01】自动总结", false, Instant.now());

        mvc.perform(post("/api/internal/agent/templates/" + t.getId() + "/change-summary?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json")
                        .content("{\"append\":\"⚠️ 近 7 天取消 AI 提案 5 次\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.new_version").value(2));

        RuleTemplateVersion latest = versionRepository
                .findTopByTemplateIdAndFamilyIdOrderByVersionDesc(t.getId(), FID).orElseThrow();
        assertThat(latest.getChangeSummary())
                .isEqualTo("【2026-08-01】自动总结\n⚠️ 近 7 天取消 AI 提案 5 次");
    }

    @Test
    void C21_append_wrongFamily_40404() throws Exception {
        RuleTemplate t = seedTemplate("家务分解");
        seedVersion(t, 2, "旧摘要", false, Instant.now());

        mvc.perform(post("/api/internal/agent/templates/" + t.getId() + "/change-summary?family_id=999999")
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json")
                        .content("{\"append\":\"越权追加\"}"))
                .andExpect(jsonPath("$.code").value(40404));
    }

    @Test
    void C21_append_noVersion_46503() throws Exception {
        RuleTemplate t = seedTemplate("空版本模板");

        mvc.perform(post("/api/internal/agent/templates/" + t.getId() + "/change-summary?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json")
                        .content("{\"append\":\"无版本\"}"))
                .andExpect(jsonPath("$.code").value(46503));
    }

    // ==================== C-22 版本清理 ====================

    @Test
    void C22_cleanup_keepsImportantAndYoungest3_deletesOldRest() throws Exception {
        RuleTemplate t = seedTemplate("清理模板");
        Instant now = Instant.now();
        seedVersion(t, 1, "重要旧版本", true, now.minus(400, ChronoUnit.DAYS));
        seedVersion(t, 2, "普通v2", false, now.minus(100, ChronoUnit.DAYS));
        seedVersion(t, 3, "普通v3", false, now.minus(90, ChronoUnit.DAYS));
        seedVersion(t, 4, "普通v4", false, now.minus(40, ChronoUnit.DAYS));
        seedVersion(t, 5, "普通v5", false, now.minus(10, ChronoUnit.DAYS));
        seedVersion(t, 6, "普通v6", false, now);

        mvc.perform(post("/api/internal/agent/template-versions/cleanup?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(2));

        List<RuleTemplateVersion> remaining = versionRepository
                .findByTemplateIdOrderByVersionDesc(t.getId());
        assertThat(remaining).hasSize(4);
        assertThat(remaining).extracting(RuleTemplateVersion::getVersion)
                .containsExactly(6, 5, 4, 1);
        assertThat(remaining).allSatisfy(v -> {
            if (v.getIsImportant()) {
                assertThat(v.getChangeSummary()).isEqualTo("重要旧版本");
            }
        });
    }

    @Test
    void C22_cleanup_otherFamilyUntouched() throws Exception {
        RuleTemplate t = seedTemplate("别家模板");
        Instant now = Instant.now();
        seedVersion(t, 1, "老版本", false, now.minus(100, ChronoUnit.DAYS));

        mvc.perform(post("/api/internal/agent/template-versions/cleanup?family_id=888888")
                        .header("X-Internal-Secret", SECRET))
                .andExpect(jsonPath("$.data.deleted").value(0));

        assertThat(versionRepository.findByTemplateIdOrderByVersionDesc(t.getId())).hasSize(1);
    }

    // ==================== C-23 活跃家庭枚举 ====================

    @Test
    void C23_activeFamilies_excludesSoftDeleted() throws Exception {
        Instant ts = Instant.now();
        Family alive = new Family();
        alive.setName("活跃家庭");
        alive.setCreatedBy(9901L);
        alive.setCreatedAt(ts);
        alive.setUpdatedAt(ts);
        alive = familyRepository.save(alive);
        Family dead = new Family();
        dead.setName("软删家庭");
        dead.setCreatedBy(9902L);
        dead.setCreatedAt(ts);
        dead.setUpdatedAt(ts);
        dead = familyRepository.save(dead);
        dead.setDeletedAt(Instant.now());
        familyRepository.save(dead);

        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/internal/agent/active-families")
                        .header("X-Internal-Secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        com.fasterxml.jackson.databind.JsonNode data = MAPPER.readTree(body).get("data");
        java.util.Set<Long> ids = new java.util.HashSet<>();
        data.forEach(n -> ids.add(n.asLong()));
        assertThat(ids).contains(alive.getId());
        assertThat(ids).doesNotContain(dead.getId());
    }

    // ==================== C-24 模板版本历史（diff 基线 / ❌2 学习轨迹） ====================

    @Test
    void C24_versions_newestFirst_withLimitAndChangeSummary() throws Exception {
        RuleTemplate t = seedTemplate("版本历史模板");
        Instant now = Instant.now();
        seedVersion(t, 1, "第一次自动总结", false, now.minus(3, ChronoUnit.DAYS));
        seedVersion(t, 2, "第二次自动总结", true, now.minus(2, ChronoUnit.DAYS));
        seedVersion(t, 3, "第三次自动总结", false, now.minus(1, ChronoUnit.DAYS));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/internal/agent/templates/" + t.getId() + "/versions"
                                + "?family_id=" + FID + "&limit=2")
                        .header("X-Internal-Secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.template_id").value(t.getId()))
                .andExpect(jsonPath("$.data.versions.length()").value(2))
                .andExpect(jsonPath("$.data.versions[0].version").value(3))
                .andExpect(jsonPath("$.data.versions[0].change_summary").value("第三次自动总结"))
                .andExpect(jsonPath("$.data.versions[0].is_important").value(false))
                .andExpect(jsonPath("$.data.versions[1].version").value(2))
                .andExpect(jsonPath("$.data.versions[1].is_important").value(true));
    }

    @Test
    void C24_versions_limitDefaultsTo10AndCapsAt50() throws Exception {
        RuleTemplate t = seedTemplate("长历史模板");
        Instant now = Instant.now();
        for (int v = 1; v <= 12; v++) {
            seedVersion(t, v, "v" + v, false, now.minus(v, ChronoUnit.DAYS));
        }

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/internal/agent/templates/" + t.getId() + "/versions?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET))
                .andExpect(jsonPath("$.data.versions.length()").value(10))
                .andExpect(jsonPath("$.data.versions[0].version").value(12));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/internal/agent/templates/" + t.getId()
                                + "/versions?family_id=" + FID + "&limit=999")
                        .header("X-Internal-Secret", SECRET))
                .andExpect(jsonPath("$.data.versions.length()").value(12));
    }

    @Test
    void C24_versions_crossFamily_40404() throws Exception {
        RuleTemplate t = seedTemplate("别家版本模板");
        seedVersion(t, 1, "别家摘要", false, Instant.now());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/internal/agent/templates/" + t.getId() + "/versions?family_id=999999")
                        .header("X-Internal-Secret", SECRET))
                .andExpect(jsonPath("$.code").value(40404));
    }
}
