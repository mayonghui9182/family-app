package com.familyapp.agent;

import com.familyapp.auth.UserContext;
import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.profile.PersonProfileService;
import com.familyapp.profile.PersonProfileRepository;
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

/**
 * T5 FinalStateGuard — 4 条守卫断言
 *
 * <p>覆盖 DecomposeSessionService.loadActiveScoped / confirm 的前置校验：
 * <ol>
 *   <li>终态调用 → 50311 AI_PROPOSAL_ALREADY_FINALIZED</li>
 *   <li>空 proposed_tasks → 50313 AI_PROPOSAL_EMPTY</li>
 *   <li>PROFILE_INTERVIEW 有 patch 但无 target → 50312 AI_PROPOSAL_NO_CONTEXT</li>
 *   <li>跨 familyId 越权访问 → 46001 AI_PROPOSAL_NOT_FOUND</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("classpath:db/v6-test-setup.sql")
class DecomposeFinalStateGuardTest {

    @Autowired
    private DecomposeSessionService service;
    @Autowired
    private DecomposeSessionRepository sessionRepo;
    @Autowired
    private PersonProfileService profileService;
    @Autowired
    private PersonProfileRepository profileRepo;

    @BeforeEach
    void setUp() {
        // family 1, userId 1 OWNER
        UserContext.setCurrent(new UserContext.CurrentUser(1L, "test@test.com", "test", 1L, "OWNER"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ==================== 辅助方法（复用 HappyPath，保持种子风格一致） ====================

    private Long createBabyProfile(Map<String, Object> initialData) {
        var req = new com.familyapp.profile.dto.CreatePersonProfileRequest();
        req.setProfileType("BABY");
        req.setName("T5-测试宝宝");
        req.setRelation("儿子");
        req.setProfileData(initialData != null ? initialData : new HashMap<>());
        return profileService.create(req).id();
    }

    private DecomposeSession saveSessionSeed(
            DecomposeSessionType sessionType,
            Map<String, Object> initProfileIdSnapshot,
            Map<String, Object> extractedProfilePatch,
            List<Object> proposedTasks,
            Long overrideFamilyId
    ) {
        var s = new DecomposeSession();
        s.setFamilyId(overrideFamilyId != null ? overrideFamilyId : 1L);
        s.setSessionType(sessionType);
        s.setStatus(DecomposeSessionStatus.ACTIVE);
        s.setInitProfileIdSnapshot(initProfileIdSnapshot != null ? initProfileIdSnapshot : new HashMap<>());
        s.setExtractedProfilePatch(extractedProfilePatch);
        s.setProposedTasks(proposedTasks != null ? proposedTasks : new ArrayList<>());
        s.setCreatedBy(1L);
        return sessionRepo.save(s);
    }

    // ========================================================================
    // T5-1 终态守卫：status != ACTIVE → 50311
    // ========================================================================
    @Test
    @DisplayName("T5-1 终态守卫: CONFIRMED 后再 confirm → 50311 AI_PROPOSAL_ALREADY_FINALIZED")
    void t51_终态_CONFIRMED_再调用_抛50311() {
        // SEED: 写入 ACTIVE session（含合法 proposedTasks），立刻手动改成 CONFIRMED
        Long babyId = createBabyProfile(Map.of("month_age", 6));
        DecomposeSession seed = saveSessionSeed(
                DecomposeSessionType.TASK_CREATE,
                Map.of("target", (Number) babyId),
                Map.of("patches", List.of(Map.of("profile_id", (Number) babyId, "month_age", 8))),
                List.of(Map.of("title", "任意任务", "due_date", "2026-09-10")),
                null
        );
        seed.setStatus(DecomposeSessionStatus.CONFIRMED);
        sessionRepo.save(seed);

        // ACT + ASSERT: confirm → 50311
        ApiException ex = assertThrows(ApiException.class,
                () -> service.confirm(seed.getId(), 1L, 1L),
                "终态 CONFIRMED 后再 confirm 必须抛 ApiException");
        assertSame(ResultCode.AI_PROPOSAL_ALREADY_FINALIZED, ex.getCode(),
                "终态错误码必须 AI_PROPOSAL_ALREADY_FINALIZED（50311）");
    }

    @Test
    @DisplayName("T5-1b 终态守卫: ARCHIVED 后 saveProfileOnly → 50311（覆盖 loadActiveScoped 所有入口）")
    void t51b_终态_ARCHIVED_再saveOnly_抛50311() {
        Long babyId = createBabyProfile(Map.of("month_age", 6));
        DecomposeSession seed = saveSessionSeed(
                DecomposeSessionType.TASK_CREATE,
                Map.of("target", (Number) babyId),
                Map.of("patches", List.of(Map.of("profile_id", (Number) babyId, "month_age", 7))),
                List.of(),
                null
        );
        seed.setStatus(DecomposeSessionStatus.ARCHIVED);
        sessionRepo.save(seed);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.saveProfileOnly(seed.getId(), 1L, 1L),
                "终态 ARCHIVED 后再 saveProfileOnly 必须抛 ApiException");
        assertSame(ResultCode.AI_PROPOSAL_ALREADY_FINALIZED, ex.getCode(),
                "终态错误码必须 AI_PROPOSAL_ALREADY_FINALIZED（50311）");
    }

    // ========================================================================
    // T5-2 空 proposal 守卫：confirm 时 proposed_tasks 空 → 50313
    // ========================================================================
    @Test
    @DisplayName("T5-2 空 proposal 守卫: proposed_tasks=[] confirm → 50313 AI_PROPOSAL_EMPTY")
    void t52_空proposedTasks_confirm_抛50313() {
        Long babyId = createBabyProfile(Map.of("month_age", 6));
        DecomposeSession seed = saveSessionSeed(
                DecomposeSessionType.TASK_CREATE,
                Map.of("target", (Number) babyId),
                Map.of("patches", List.of(Map.of("profile_id", (Number) babyId, "month_age", 7))),
                List.of(),   // proposedTasks 空 → 触发 50313
                null
        );

        ApiException ex = assertThrows(ApiException.class,
                () -> service.confirm(seed.getId(), 1L, 1L),
                "proposed_tasks 空 confirm 必须抛 ApiException");
        assertSame(ResultCode.AI_PROPOSAL_EMPTY, ex.getCode(),
                "空 proposal 错误码必须 AI_PROPOSAL_EMPTY（50313）");
    }

    // ========================================================================
    // T5-3 缺 context 守卫：PROFILE_INTERVIEW + 有非空 patch + 无 target profile → 50312
    //   通过 T2 Fix R1 的 VARCHAR(20) 扩列验证，PROFILE_INTERVIEW 必须能成功 save
    // ========================================================================
    @Test
    @DisplayName("T5-3 缺上下文守卫: PROFILE_INTERVIEW 有 patch 但无 target → 50312 AI_PROPOSAL_NO_CONTEXT")
    void t53_PROFILE_INTERVIEW_缺target_抛50312() {
        // 关键：
        //   ① sessionType = PROFILE_INTERVIEW（验证 VARCHAR(20) 扩列生效）
        //   ② extractedProfilePatch 非空（hasPatch == true）
        //   ③ profileId = null + initProfileIdSnapshot 无 target key → hasAnyProfileTarget == null
        //   ④ proposedTasks 非空（先进 proposed.isEmpty() 分支，继续进入 PROFILE_INTERVIEW 分支）
        Map<String, Object> patchWithContent = Map.of(
                "patches", List.of(
                        Map.of("month_age", 10, "milk_brand", "飞鹤")  // 无 profile_id 字段
                )
        );

        DecomposeSession seed = saveSessionSeed(
                DecomposeSessionType.PROFILE_INTERVIEW,   // VARCHAR(20) 验证点
                new HashMap<>(),                          // 空 snapshot → 无 target
                patchWithContent,                         // hasPatch = true
                List.of(Map.of("title", "应在 50312 之前被拦的任务", "due_date", "2026-10-01")),
                null
        );
        // 再确保 profileId 字段显式为 null（saveSessionSeed 默认已 null，这里再明确断言）
        assertNull(seed.getProfileId(), "种子 profileId 必须 null 才能触发 缺 context");

        ApiException ex = assertThrows(ApiException.class,
                () -> service.confirm(seed.getId(), 1L, 1L),
                "PROFILE_INTERVIEW 有 patch 但无 target 必须抛 ApiException");
        assertSame(ResultCode.AI_PROPOSAL_NO_CONTEXT, ex.getCode(),
                "缺 context 错误码必须 AI_PROPOSAL_NO_CONTEXT（50312）");
    }

    // ========================================================================
    // T5-4 越权守卫：session familyId=1，调 service 传 familyId=2 → 46001 NOT_FOUND
    // ========================================================================
    @Test
    @DisplayName("T5-4 越权守卫: session 属 family 1，用 family 2 调 confirm → 46001 AI_PROPOSAL_NOT_FOUND")
    void t54_跨family越权_抛46001() {
        Long babyId = createBabyProfile(Map.of("month_age", 6));
        DecomposeSession seed = saveSessionSeed(
                DecomposeSessionType.TASK_CREATE,
                Map.of("target", (Number) babyId),
                Map.of("patches", List.of(Map.of("profile_id", (Number) babyId, "month_age", 8))),
                List.of(Map.of("title", "越权测试不应创建的任务", "due_date", "2026-09-15")),
                1L   // overrideFamilyId = 1
        );

        // 用 familyId = 2 调 confirm（当前 UserContext family=1 但 service 层走 findByIdAndFamilyId）
        ApiException ex = assertThrows(ApiException.class,
                () -> service.confirm(seed.getId(), 2L, 1L),
                "跨 family 访问必须抛 ApiException（findByIdAndFamilyId 返回空 → AI_PROPOSAL_NOT_FOUND）");
        assertSame(ResultCode.AI_PROPOSAL_NOT_FOUND, ex.getCode(),
                "越权错误码必须 AI_PROPOSAL_NOT_FOUND（46001）");

        // 副作用断言：session 仍 ACTIVE（confirm 未执行任何写）
        assertEquals(DecomposeSessionStatus.ACTIVE,
                sessionRepo.findById(seed.getId()).orElseThrow().getStatus(),
                "越权被拒后 session.status 必须保持 ACTIVE，不能推进终态");
    }
}
