package com.familyapp.agent;

import com.familyapp.auth.UserContext;
import com.familyapp.profile.PersonProfileService;
import com.familyapp.profile.PersonProfileRepository;
import com.familyapp.task.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("classpath:db/v6-test-setup.sql")
class DecomposeHappyPathIntegrationTest {

    @Autowired
    private DecomposeSessionService service;

    @Autowired
    private DecomposeSessionRepository sessionRepo;
    @Autowired
    private PersonProfileService profileService;
    @Autowired
    private PersonProfileRepository profileRepo;
    @Autowired
    private TaskRepository taskRepo;

    @BeforeEach
    void setUp() {
        UserContext.setCurrent(new UserContext.CurrentUser(1L, "test@test.com", "test", 1L, "OWNER"));
    }
    @AfterEach
    void tearDown() { UserContext.clear(); }

    private Long createBabyProfile(Map<String, Object> initialData) {
        var req = new com.familyapp.profile.dto.CreatePersonProfileRequest();
        req.setProfileType("BABY");
        req.setName("测试宝宝");
        req.setRelation("儿子");
        req.setProfileData(initialData);
        return profileService.create(req).id();
    }

    private DecomposeSession saveSessionSeed(
            DecomposeSessionType sessionType,
            Map<String, Object> initProfileIdSnapshot,
            Map<String, Object> extractedProfilePatch,
            List<Object> proposedTasks,
            Map<String, Object> taskContext
    ) {
        var s = new DecomposeSession();
        s.setFamilyId(1L);
        s.setSessionType(sessionType);
        s.setStatus(DecomposeSessionStatus.ACTIVE);
        s.setInitProfileIdSnapshot(initProfileIdSnapshot != null ? initProfileIdSnapshot : new HashMap<>());
        s.setExtractedProfilePatch(extractedProfilePatch);
        s.setProposedTasks(proposedTasks != null ? proposedTasks : new ArrayList<>());
        s.setCreatedBy(1L);
        if (taskContext != null && taskContext.containsKey("task_id")) {
            Object tid = taskContext.get("task_id");
            if (tid instanceof Number n && n.longValue() > 0) {
                s.setInitTaskId(n.longValue());
            }
        }
        return sessionRepo.save(s);
    }

    @Test
    @DisplayName("D-5 confirm HappyPath: 2 任务创建成功 + 1 档案 merge(合法) + discard(非法key) + status=CONFIRMED")
    void d5Confirm_2任务1档案_全链路成功() {
        // ==================== SEED ====================
        Long babyId = createBabyProfile(Map.of("month_age", 6, "allergy_list", List.of("牛奶")));

        Map<String, Object> extractedPatch = new HashMap<>();
        extractedPatch.put("patches", List.of(
            Map.of(
                "profile_id", (Number) babyId,
                "month_age", 9,
                "milk_brand", "爱他美",
                "new_skill_not_in_whitelist", "抬头爬行",
                "remove_keys", List.of("allergy_list")
            )
        ));

        DecomposeSession seed = saveSessionSeed(
            DecomposeSessionType.TASK_CREATE,
            Map.of("target", (Number) babyId),
            extractedPatch,
            List.of(
                Map.of("title", "给宝宝喂高铁米糊",
                       "description", "早上 8 点，加 1 勺核桃油",
                       "priority", "HIGH",
                       "due_date", "2026-09-02",
                       "assignee_profile_id", (Number) babyId,
                       "category_id", 1),
                Map.of("title", "宝宝 9 月龄体检疫苗接种",
                       "description", "社区卫生服务中心 9 点，带疫苗本",
                       "priority", "MEDIUM",
                       "due_date", "2026-09-05")
            ),
            Map.of("task_id", 0)
        );

        // ==================== ACT ====================
        var resp = service.confirm(seed.getId(), 1L, 1L);

        // ==================== ASSERT ====================
        // A. session 状态终态 CONFIRMED
        assertEquals(DecomposeSessionStatus.CONFIRMED,
            sessionRepo.findById(seed.getId()).orElseThrow().getStatus(),
            "D-5 后 session.status 必须 CONFIRMED");

        // B. 返回 DTO：2 个任务 id 生成 + 1 个档案 id 合并
        assertEquals(2, resp.created_task_ids().size(), "应生成 2 条任务");
        assertEquals(1, resp.merged_profile_ids().size(), "应合并 1 条档案");
        assertTrue(resp.merged_profile_ids().contains(babyId), "合并档案 id 应包含 babyId");

        // C. 非法 key discard 报告
        long newSkillDiscardCount = resp.discarded_keys().stream()
            .filter(k -> "new_skill_not_in_whitelist".equals(k))
            .count();
        assertTrue(newSkillDiscardCount >= 1, "非法 key 应出现在 discardedKeys 报告中（soft discard）");

        // D. profile_data 真实更新：合法 merge + 非法 key 不在 + remove_keys 指定的 key 已删除
        Map<String, Object> pd = profileRepo.findById(babyId).orElseThrow().getProfileData();
        assertEquals(9, pd.get("month_age"), "合法 month_age 应从 6 覆盖成 9");
        assertEquals("爱他美", pd.get("milk_brand"), "合法 milk_brand 应写入");
        assertFalse(pd.containsKey("new_skill_not_in_whitelist"), "非法 key 不应写入 profile_data");
        assertFalse(pd.containsKey("allergy_list"), "remove_keys 指定的 allergy_list 应被删除");

        // E. 任务表真正写了 2 条，字段正确
        var tasks = taskRepo.findByFamilyIdAndDeletedAtIsNull(1L);
        assertEquals(2, tasks.size(), "tasks 表应为 family 1 新增 2 条");

        var t1 = tasks.stream().filter(t -> "给宝宝喂高铁米糊".equals(t.getTitle())).findFirst().orElseThrow();
        assertEquals(babyId, t1.getAssigneeProfileId(), "任务 1 应指派到 baby 档案（assignee_profile_id 字段）");
        assertEquals(java.time.LocalDate.parse("2026-09-02"), t1.getDueDate(), "任务 1 dueDate 解析正确");

        var t2 = tasks.stream().filter(t -> "宝宝 9 月龄体检疫苗接种".equals(t.getTitle())).findFirst().orElseThrow();
        assertEquals(java.time.LocalDate.parse("2026-09-05"), t2.getDueDate(), "任务 2 dueDate 解析正确");
        assertNull(t2.getAssigneeProfileId(), "任务 2 未指定 assignee_profile_id，应为 null（不兼容旧 assignee_id）");
    }

    @Test
    @DisplayName("D-6 save-profile-only HappyPath: 1 档案 patch 全合法 merge + tasks 零条 + status=ARCHIVED（跳过任务写入）")
    void d6SaveOnly_档案合并_不生成任务() {
        // ==================== SEED ====================
        Long babyId = createBabyProfile(Map.of("month_age", 6));

        Map<String, Object> extractedPatch = Map.of(
            "patches", List.of(
                Map.of(
                    "profile_id", (Number) babyId,
                    "month_age", 8,
                    "common_meds", List.of("益生菌", "维生素D3")
                )
            )
        );

        DecomposeSession seed = saveSessionSeed(
            DecomposeSessionType.TASK_CREATE,
            Map.of("target", (Number) babyId),
            extractedPatch,
            List.of(),
            Map.of()
        );

        // ==================== ACT ====================
        var resp = service.saveProfileOnly(seed.getId(), 1L, 1L);

        // ==================== ASSERT ====================
        // A. 档案 patch 合并成功
        assertTrue(resp.merged_profile_ids().contains(babyId), "合并档案应包含 babyId");
        Map<String, Object> pd = profileRepo.findById(babyId).orElseThrow().getProfileData();
        assertEquals(8, pd.get("month_age"), "month_age 应更新为 8");
        assertEquals(List.of("益生菌", "维生素D3"), pd.get("common_meds"), "common_meds 列表应写入");

        // B. 零写任务 — tasks 表 family 1 记录数 = 0
        long taskCount = taskRepo.findAll().stream()
            .filter(t -> Long.valueOf(1L).equals(t.getFamilyId()))
            .count();
        assertEquals(0, taskCount, "D-6 save-profile-only 绝对不能写 tasks 表");

        // C. 会话终态 ARCHIVED
        assertEquals(DecomposeSessionStatus.ARCHIVED,
            sessionRepo.findById(seed.getId()).orElseThrow().getStatus(),
            "D-6 后 status 应为 ARCHIVED");

        // D. 合法的业务 key 不应被 discard（profile_id 结构性字段被 discard 属正常）
        List<String> businessKeys = List.of("month_age", "common_meds");
        for (String bk : businessKeys) {
            assertFalse(resp.discarded_keys() != null && resp.discarded_keys().contains(bk),
                "合法业务 key " + bk + " 不应出现在 discardedKeys 中");
        }
    }

    @Test
    @DisplayName("D-6 跨家庭档案引用按不存在丢弃且不得修改档案")
    void d6SaveOnly_跨家庭档案不修改() {
        Long babyId = createBabyProfile(Map.of("month_age", 6));
        DecomposeSession seed = saveSessionSeed(
            DecomposeSessionType.PROFILE_INTERVIEW,
            Map.of("target", (Number) babyId),
            Map.of("profile_id", (Number) babyId, "month_age", 18),
            List.of(),
            Map.of()
        );
        // agent_decompose_sessions 的 family_id 无外键，可直接构造跨家庭攻击会话。
        seed.setFamilyId(2L);
        sessionRepo.save(seed);

        var resp = service.saveProfileOnly(seed.getId(), 2L, 1L);

        assertFalse(resp.merged_profile_ids().contains(babyId), "跨家庭档案不得记为已合并");
        assertTrue(resp.discarded_keys().contains("profile_id:" + babyId + ":NOT_FOUND"),
            "跨家庭档案必须按不存在处理，避免泄漏");
        Map<String, Object> unchanged = profileRepo.findById(babyId).orElseThrow().getProfileData();
        assertEquals(6, unchanged.get("month_age"), "跨家庭 patch 不得修改原档案");
    }


    @Test
    @DisplayName("D-7 cancel HappyPath: ACTIVE → CANCELLED，profile_data 原值保留 + tasks 表 0 新增（零写业务表）")
    void d7Cancel_终态流转_业务表零写() {
        // ==================== SEED ====================
        Long babyId = createBabyProfile(Map.of("month_age", 6));

        Map<String, Object> extractedPatch = Map.of(
            "patches", List.of(
                Map.of(
                    "profile_id", (Number) babyId,
                    "month_age", 99
                )
            )
        );

        DecomposeSession seed = saveSessionSeed(
            DecomposeSessionType.TASK_CREATE,
            Map.of("target", (Number) babyId),
            extractedPatch,
            List.of(Map.of("title", "不该出现的任务", "due_date", "2099-01-01")),
            Map.of("task_id", 0)
        );

        // ==================== ACT ====================
        service.cancel(seed.getId(), 1L, 1L);

        // ==================== ASSERT ====================
        // A. session 状态 CANCELLED
        assertEquals(DecomposeSessionStatus.CANCELLED,
            sessionRepo.findById(seed.getId()).orElseThrow().getStatus(),
            "D-7 后 status 应为 CANCELLED");

        // B. profile_data 完全没变（month_age 还是 6，不是 99）
        Map<String, Object> pd = profileRepo.findById(babyId).orElseThrow().getProfileData();
        assertEquals(6, pd.get("month_age"), "D-7 cancel 后档案必须保留原始值，不能应用 patch");

        // C. tasks 表 0 新增（"不该出现的任务" 绝对没写进去）
        long taskCount = taskRepo.findAll().stream()
            .filter(t -> Long.valueOf(1L).equals(t.getFamilyId()))
            .count();
        assertEquals(0, taskCount, "D-7 cancel 后 tasks 表必须 0 新增");

        // D. session 草稿字段不变（patch/proposedTasks 仍保留以便后续审计；status 以外字段不动）
        DecomposeSession after = sessionRepo.findById(seed.getId()).orElseThrow();
        assertNotNull(after.getExtractedProfilePatch(), "extractedProfilePatch 草稿字段不应被清空");
        assertFalse(after.getProposedTasks().isEmpty(), "proposedTasks 草稿字段不应被清空");
    }
}
