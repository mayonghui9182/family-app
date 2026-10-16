package com.familyapp.agent;

import com.familyapp.profile.PersonProfile;
import com.familyapp.profile.PersonProfileRepository;
import com.familyapp.profile.ProfileType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Internal Agent B 组 4 端点集成测试（B-3/B-4/B-5/B-6）
 *
 * <p>验证对象：InternalAgentController 追加的 4 方法（plan Step 7）
 *
 * <p>设计依据（plan Step 7 §InternalAgentController B-3~B-6 + plan File Structure §InternalAgentBGroupTest）：
 * <ol>
 *   <li>B-3 POST /api/internal/agent/profiles/get-by-family：body {family_id:Long} → List&lt;PersonProfileResponse&gt;</li>
 *   <li>B-4 POST /api/internal/agent/profiles/patch-many：MVP 留占位（501 Not Implemented）</li>
 *   <li>B-5 POST /api/internal/agent/sessions/{id}/extract-patch：body {extracted_patch: object} → UPDATE agent_decompose_sessions.extracted_profile_patch（仅 ACTIVE 允许）</li>
 *   <li>B-6 POST /api/internal/agent/sessions/{id}/save-proposal：body {proposed_tasks:[...]} → UPDATE session.proposed_tasks（仅 ACTIVE）</li>
 * </ol>
 *
 * <p>权限机制（plan File Structure 注释）：
 * <ul>
 *   <li>controller 路径前缀已存在 /api/internal/agent/**，InternalSecretInterceptor 已拦截</li>
 *   <li>无 Internal-Secret 头 → 401x（Change 2 已存在拦截器码，本测试不重复验证）</li>
 *   <li>所有 B 组端点必须带 X-Internal-Secret: test-secret-correct 头</li>
 * </ul>
 *
 * <p>测试模式（参考 InternalAgentC1ToC18Test）：
 * @SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("internal-api-test")
 * + @TestPropertySource(properties = "internal-api.secret=test-secret-correct")
 * + @Transactional + MockMvc + X-Internal-Secret 头
 *
 * <p>TDD 状态：
 * <ul>
 *   <li>RED 阶段（Step 1c）：InternalAgentController B-3~B-6 方法不存在 → 404 → 测试 FAIL</li>
 *   <li>GREEN 阶段（Step 8）：Step 7 写完 B-3~B-6 4 端点 → 全 PASS</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
// 顺序：先 "test"（H2 方言 + ddl-auto + 其他通用配置），后 "internal-api-test"（覆盖 auth.enabled=true 以校验秘钥）
@ActiveProfiles({"test", "internal-api-test"})
@TestPropertySource(properties = "internal-api.secret=test-secret-correct")
@Transactional
class InternalAgentBGroupTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET = "test-secret-correct";
    private static final Long FID = 9201L; // B 组测试专用家庭 ID，避免与 C 组 9101 冲突

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PersonProfileRepository personProfileRepository;

    @Autowired
    private DecomposeSessionRepository decomposeSessionRepository;

    /** 预置测试家庭 + 2 个 person_profiles，供 B-3 list 验证。
     *  family/user 用 JDBC 原生写；profile_data/tags 等 JSON 列用 JPA Repository.save() 写，
     *  避免 H2 `CAST('{}' AS JSON)` 导致 Hibernate 反序列化时把 JSON 当字符串二次包成 `"\"{}\""`。
     */
    private void seedFamilyAndProfiles() {
        personProfileRepository.deleteAll(personProfileRepository.findByFamilyIdAndDeletedAtIsNull(FID));
        jdbcTemplate.update("DELETE FROM families WHERE id = ?", FID);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (9201, 9202)");

        jdbcTemplate.update(
            "INSERT INTO users (id, email, nickname, password_hash, created_at, updated_at) " +
            "VALUES (9201, 'b3_test1@family.app', '爸爸', '$2a$10$placeholder', NOW(), NOW())," +
            "       (9202, 'b3_test2@family.app', '妈妈', '$2a$10$placeholder', NOW(), NOW())");
        jdbcTemplate.update(
            "INSERT INTO families (id, name, created_by, created_at, updated_at) " +
            "VALUES (?, 'B组测试家庭', 9201, NOW(), NOW())", FID);

        PersonProfile dad = new PersonProfile();
        dad.setFamilyId(FID);
        dad.setUserId(9201L);
        dad.setProfileType(ProfileType.ADULT);
        dad.setName("爸爸");
        dad.setAvatarEmoji("👨");
        dad.setRelation("本人");
        dad.setTags(java.util.List.of());
        dad.setProfileData(new java.util.HashMap<>());
        dad.setCreatedBy(9201L);
        personProfileRepository.save(dad);

        PersonProfile mom = new PersonProfile();
        mom.setFamilyId(FID);
        mom.setUserId(9202L);
        mom.setProfileType(ProfileType.ADULT);
        mom.setName("妈妈");
        mom.setAvatarEmoji("👩");
        mom.setRelation("本人");
        mom.setTags(java.util.List.of());
        mom.setProfileData(new java.util.HashMap<>());
        mom.setCreatedBy(9202L);
        personProfileRepository.save(mom);
    }

    /** 预置一个 ACTIVE 状态的 agent_decompose_session，供 B-5/B-6 验证。
     *  messages/proposed_tasks 等 JSON 列用 JPA 写，理由同上。 */
    private Long seedActiveSession() {
        DecomposeSession s = new DecomposeSession();
        s.setFamilyId(FID);
        s.setSessionType(DecomposeSessionType.TASK_CREATE);
        s.setStatus(DecomposeSessionStatus.ACTIVE);
        s.setInitTaskTitle("B组测试任务");
        s.setMessages(new ArrayList<>());
        s.setCreatedBy(9201L);
        return decomposeSessionRepository.save(s).getId();
    }

    // ==================== B-3：family 维度查询档案列表 ====================

    @Test
    @DisplayName("1. B-3 POST /profiles/get-by-family body{family_id} → 200 + 返回 List<PersonProfileResponse>")
    void b3_getProfilesByFamily_返回家庭档案列表() throws Exception {
        seedFamilyAndProfiles();

        mvc.perform(post("/api/internal/agent/profiles/get-by-family")
                .header("X-Internal-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("family_id", FID))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data", hasSize(2)))
            .andExpect(jsonPath("$.data[0].profileType").value("ADULT"))
            .andExpect(jsonPath("$.data[1].profileType").value("ADULT"));
    }

    // ==================== B-4：patch-many MVP 占位 ====================

    @Test
    @DisplayName("2. B-4 POST /profiles/patch-many → 501 Not Implemented（MVP 占位，真正写档案走 D-5/D-6）")
    void b4_patchMany_返回501() throws Exception {
        mvc.perform(post("/api/internal/agent/profiles/patch-many")
                .header("X-Internal-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.code").value(501));
    }

    // ==================== B-5：更新 session.extracted_profile_patch ====================

    @Test
    @DisplayName("3. B-5 POST /sessions/{id}/extract-patch body{extracted_patch} → 200 + session.extracted_profile_patch 更新")
    void b5_extractPatch_更新会话extractedProfilePatch() throws Exception {
        seedFamilyAndProfiles();
        Long sessionId = seedActiveSession();
        Map<String, Object> extractedPatch = Map.of(
            "month_age", 9,
            "allergy_list", List.of("牛奶"));

        mvc.perform(post("/api/internal/agent/sessions/" + sessionId + "/extract-patch")
                .param("family_id", FID.toString())
                .header("X-Internal-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("extracted_patch", extractedPatch))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        // 用 repository 读一级缓存（避免 @Transactional 未 flush 到 DB 时，JDBC 直接读看不到）
        DecomposeSession s = decomposeSessionRepository.findById(sessionId)
                .orElseThrow(() -> new AssertionError("session id=" + sessionId + " 没找到"));
        var patch = s.getExtractedProfilePatch();
        org.junit.jupiter.api.Assertions.assertNotNull(patch, "extracted_profile_patch 不应为 null");
        org.junit.jupiter.api.Assertions.assertEquals(9, patch.get("month_age"),
            "extracted_profile_patch 应包含 month_age=9，实际 patch=" + patch);
    }

    // ==================== B-6：更新 session.proposed_tasks ====================

    @Test
    @DisplayName("4. B-6 POST /sessions/{id}/save-proposal body{proposed_tasks} → 200 + session.proposed_tasks 更新")
    void b6_saveProposal_更新会话proposedTasks() throws Exception {
        seedFamilyAndProfiles();
        Long sessionId = seedActiveSession();
        List<Map<String, Object>> proposedTasks = List.of(
            Map.of("title", "喂奶", "priority", "HIGH"),
            Map.of("title", "换尿布", "priority", "MEDIUM"));

        mvc.perform(post("/api/internal/agent/sessions/" + sessionId + "/save-proposal").param("family_id", FID.toString())
                .header("X-Internal-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("proposed_tasks", proposedTasks))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        // 同 B-5：repository 读一级缓存，避免 JDBC 跨事务读不到未 flush 的 Hibernate 写
        DecomposeSession s = decomposeSessionRepository.findById(sessionId)
                .orElseThrow(() -> new AssertionError("session id=" + sessionId + " 没找到"));
        var tasks = s.getProposedTasks();
        org.junit.jupiter.api.Assertions.assertNotNull(tasks, "proposed_tasks 不应为 null");
        @SuppressWarnings("unchecked")
        var titles = tasks.stream().map(t -> ((Map<String, Object>) t).get("title")).toList();
        org.junit.jupiter.api.Assertions.assertTrue(
            titles.contains("喂奶") && titles.contains("换尿布"),
            "proposed_tasks 应包含两个任务标题，实际 titles=" + titles);
    }

    @Test
    @DisplayName("5. B-5/B-7 按 family_id 隔离，跨家庭写入统一拒绝")
    void b5AndB7_rejectCrossFamilyAndB7AllowsScopedAppend() throws Exception {
        seedFamilyAndProfiles();
        Long sessionId = seedActiveSession();

        mvc.perform(post("/api/internal/agent/sessions/" + sessionId + "/extract-patch")
                .param("family_id", "999999")
                .header("X-Internal-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                        "extracted_patch", Map.of("month_age", 99)))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(46001));

        mvc.perform(post("/api/internal/agent/sessions/" + sessionId + "/messages")
                .param("family_id", "999999")
                .header("X-Internal-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                        "role", "user", "content", "跨家庭消息"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(46001));

        DecomposeSession unchanged = decomposeSessionRepository.findById(sessionId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertNull(unchanged.getExtractedProfilePatch());
        org.junit.jupiter.api.Assertions.assertTrue(unchanged.getMessages().isEmpty());

        mvc.perform(post("/api/internal/agent/sessions/" + sessionId + "/messages")
                .param("family_id", FID.toString())
                .header("X-Internal-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                        "role", "assistant", "content", "同家庭消息"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        DecomposeSession updated = decomposeSessionRepository.findById(sessionId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(1, updated.getMessages().size());
    }
}
