package com.familyapp.agent;

import com.familyapp.auth.UserContext;
import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.profile.PersonProfileRepository;
import com.familyapp.profile.PersonProfileService;
import com.familyapp.task.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T6 DecomposeAtomicRollback — D-5 confirm @Transactional 原子性验证
 *
 * <p><b>核心场景</b>：service.confirm() 内部执行顺序为
 * <pre>
 *   1. applyExtractedPatchToLinkedProfiles → 写 person_profiles.profile_data
 *   2. for (3 proposedTasks) taskService.create → 写 tasks 表（第 3 条 due_date="not-a-date" 抛 BAD_REQUEST_VALIDATION）
 *   3. s.setStatus(CONFIRMED) + sessionRepo.save
 * </pre>
 * 第 3 条任务在 confirm.for 的最末迭代抛 {@link ApiException} (RuntimeException)
 * → 必须触发整个 @Transactional 回滚，前 2 条写入 + patch + status 全部撤销。
 *
 * <p><b>关键设计：测试类不加 @Transactional</b>
 * 原因：Spring 测试默认 @Transactional 在结束时自动 rollback，会掩盖 confirm 事务
 * 「有没有真的回滚」的判定。seed 与 cleanup 显式使用 TransactionTemplate 小事务提交；
 * confirm() 走 Service 自己的 @Transactional（真实提交/回滚）；断言直接查 DB 持久化状态。
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql("classpath:db/v6-test-setup.sql")
class DecomposeAtomicRollbackTest {

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
    @Autowired
    private PlatformTransactionManager txManager;

    /** 独立小事务执行（auto-commit 后 DB 可见，不依赖外层 @Transactional） */
    private <T> T doInTx(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }
    private void doInTx(Runnable r) {
        new TransactionTemplate(txManager).execute(status -> { r.run(); return null; });
    }

    @BeforeEach
    void setUp() {
        UserContext.setCurrent(new UserContext.CurrentUser(1L, "test@test.com", "test", 1L, "OWNER"));
    }

    @AfterEach
    void tearDown() {
        // 显式清理 family 1 的本测试产生数据（类无 @Transactional，不会自动回滚）
        try {
            doInTx(() -> {
                taskRepo.deleteAll(taskRepo.findByFamilyIdAndDeletedAtIsNull(1L));
                sessionRepo.deleteAll(sessionRepo.findAll().stream()
                        .filter(s -> Long.valueOf(1L).equals(s.getFamilyId()))
                        .toList());
                profileRepo.deleteAll(profileRepo.findAll().stream()
                        .filter(p -> Long.valueOf(1L).equals(p.getFamilyId()))
                        .toList());
            });
        } finally {
            UserContext.clear();
        }
    }

    // ========================================================================
    // T6 唯一主测试：3 proposedTasks 中第 3 条抛异常 → 全表回滚 0 写入
    // ========================================================================
    @Test
    @DisplayName("T6-1 事务回滚: 第 3 条任务 due_date 格式非法抛 BAD_REQUEST_VALIDATION → tasks/profile/session 全部回滚")
    void t61_第三条任务失败_全表回滚() {
        // ==================== SEED（独立小事务提交到 DB） ====================
        final Long babyId = doInTx(() -> {
            var req = new com.familyapp.profile.dto.CreatePersonProfileRequest();
            req.setProfileType("BABY");
            req.setName("T6-回滚-测试宝宝");
            req.setRelation("女儿");
            req.setProfileData(Map.of("month_age", 6, "allergy_list", List.of("鸡蛋")));
            return profileService.create(req).id();
        });
        assertNotNull(babyId, "babyId 必须已创建");

        // — 种子时 snapshot 的 babyId / profile_id 引用必须是同一个
        final Long snapshotBabyId = babyId;
        final Long sessionId = doInTx(() -> {
            var s = new DecomposeSession();
            s.setFamilyId(1L);
            s.setSessionType(DecomposeSessionType.TASK_CREATE);
            s.setStatus(DecomposeSessionStatus.ACTIVE);
            s.setInitProfileIdSnapshot(Map.of("target", (Number) snapshotBabyId));
            // profile patch: month_age 6→10 + remove allergy_list（如果没回滚会真的生效）
            Map<String, Object> extracted = new HashMap<>();
            extracted.put("patches", List.of(
                    Map.of("profile_id", (Number) snapshotBabyId,
                            "month_age", 10,
                            "remove_keys", List.of("allergy_list"))
            ));
            s.setExtractedProfilePatch(extracted);
            // 3 条 proposedTasks: 前 2 合法，第 3 条 due_date="not-a-date" → BAD_REQUEST_VALIDATION
            List<Object> proposed = new ArrayList<>();
            proposed.add(Map.of("title", "回滚测试-不该出现的任务1",
                    "description", "喂配方奶 150ml",
                    "priority", "HIGH",
                    "due_date", "2026-09-03",
                    "assignee_profile_id", (Number) snapshotBabyId,
                    "category_id", 1));
            proposed.add(Map.of("title", "回滚测试-不该出现的任务2",
                    "description", "换尿布 + 拍嗝",
                    "priority", "MEDIUM",
                    "due_date", "2026-09-04"));
            proposed.add(Map.of("title", "回滚测试-非法字段触发",
                    "description", "这条 due_date 非法会抛 BAD_REQUEST_VALIDATION",
                    "priority", "LOW",
                    "due_date", "not-a-date"));   // ← 触发点：toCreateTaskRequest L208
            s.setProposedTasks(proposed);
            s.setCreatedBy(1L);
            return sessionRepo.save(s).getId();
        });

        // ==================== 前置快照：回滚基线 ====================
        // — 确认 confirm 之前 profile_data/tasks/session.status 的初始值
        int taskCountBefore = doInTx(() -> taskRepo.findByFamilyIdAndDeletedAtIsNull(1L).size());
        assertEquals(0, taskCountBefore, "confirm 前 tasks 表 family 1 必须 0 条（基线）");

        int monthAgeBefore = doInTx(() ->
                ((Number) profileRepo.findById(babyId).orElseThrow().getProfileData().get("month_age")).intValue()
        );
        assertEquals(6, monthAgeBefore, "confirm 前 month_age 必须为 6（基线）");

        boolean allergyBefore = doInTx(() ->
                profileRepo.findById(babyId).orElseThrow().getProfileData().containsKey("allergy_list")
        );
        assertTrue(allergyBefore, "confirm 前 allergy_list 必须存在（基线）");

        DecomposeSessionStatus statusBefore = doInTx(() ->
                sessionRepo.findById(sessionId).orElseThrow().getStatus()
        );
        assertSame(DecomposeSessionStatus.ACTIVE, statusBefore, "confirm 前 session.status 必须 ACTIVE");

        // ==================== ACT：confirm → 预期抛 BAD_REQUEST_VALIDATION ====================
        ApiException ex = assertThrows(ApiException.class,
                () -> service.confirm(sessionId, 1L, 1L),
                "第 3 条 proposedTasks due_date 非法必须抛 BAD_REQUEST_VALIDATION");
        assertSame(ResultCode.BAD_REQUEST_VALIDATION, ex.getCode(),
                "失败码必须 BAD_REQUEST_VALIDATION（due_date 格式检查触发）");

        // ==================== ASSERT：全表回滚 0 写入 ====================
        // A. tasks 表：0 新增（连前 2 条合法的也必须撤销，不能残留半写）
        int taskCountAfter = doInTx(() -> taskRepo.findByFamilyIdAndDeletedAtIsNull(1L).size());
        assertEquals(0, taskCountAfter,
                "confirm 抛异常后 tasks 必须 0 新增 — 前 2 条任务必须回滚，不得残留半写数据");

        // B. profile_data：month_age 必须仍为 6（不能变 10）；allergy_list 必须仍存在（不能被 remove_keys 删除）
        int monthAgeAfter = doInTx(() ->
                ((Number) profileRepo.findById(babyId).orElseThrow().getProfileData().get("month_age")).intValue()
        );
        assertEquals(6, monthAgeAfter,
                "confirm 抛异常后 month_age 必须保持 6 — patch 的 month_age=10 必须回滚");

        boolean allergyAfter = doInTx(() ->
                profileRepo.findById(babyId).orElseThrow().getProfileData().containsKey("allergy_list")
        );
        assertTrue(allergyAfter,
                "confirm 抛异常后 allergy_list 必须仍存在 — remove_keys 语义必须回滚");

        // C. session.status：必须仍 ACTIVE（不能推进到 CONFIRMED）
        DecomposeSessionStatus statusAfter = doInTx(() ->
                sessionRepo.findById(sessionId).orElseThrow().getStatus()
        );
        assertSame(DecomposeSessionStatus.ACTIVE, statusAfter,
                "confirm 抛异常后 session.status 必须保持 ACTIVE — 终态推进必须回滚");
    }
}
