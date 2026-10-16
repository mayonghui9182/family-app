package com.familyapp.profile;

import com.familyapp.profile.dto.UpdatePersonProfileRequest;
import com.familyapp.auth.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * profile_data JSONB patch + remove_keys 行为测试
 *
 * <p>验证对象：PersonProfileService.applyProfilePatch(familyId, profileId, patch, removeKeys)
 * 与 ProfileSchemaTest 的区别：
 * <ul>
 *   <li>ProfileSchemaTest：纯 schema 校验逻辑（ProfileDataSchema.validatePatch 单元测试，无 DB 无 Service）</li>
 *   <li>本测试：Service 层协作行为（schema 校验 + JSONB merge + remove_keys + 业务流程不阻断）</li>
 * </ul>
 *
 * <p>设计依据（plan Step 1c 第 4 项 + spec §1.2 + §2 P-4）：
 * <ol>
 *   <li>D-5/D-6 patch 超白名单 key → 丢弃 + 业务流程继续（不阻断任务创建/档案保存）</li>
 *   <li>D-5/D-6 patch 非法类型 → 丢弃 + 业务流程继续</li>
 *   <li>合法 key merge 成功 → profile_data 更新</li>
 *   <li>remove_keys 删除顶层 key → 200 + key 被删</li>
 *   <li>空 patch + 空 removeKeys → discardedKeys 空数组 + 无变化</li>
 * </ol>
 *
 * <p>JSONB merge 策略（spec §1.2 + plan §B 核心决策 1）：
 * <ul>
 *   <li>顶层浅合并 existing || new_patch（PostgreSQL || 语义）</li>
 *   <li>删除 key 走 remove_keys[] 字段（不能用 patch 传 null 删除）</li>
 *   <li>patch + remove_keys 同 key → 先 merge 再 remove（最终 key 被删）</li>
 *   <li>remove_keys 删除不存在 key → 幂等无变化（不报错）</li>
 * </ul>
 *
 * <p>API 设计（plan Step 6 §PersonProfileServiceImpl）：
 * <pre>
 *   class PersonProfileServiceImpl {
 *     PatchResult applyProfilePatch(Long familyId, Long profileId, Map&lt;String, Object&gt; patch, List&lt;String&gt; removeKeys);
 *   }
 *   record PatchResult(Map&lt;String, Object&gt; mergedProfileData, List&lt;Discard&gt; discardedKeys) {}
 * </pre>
 *
 * <p>TDD 状态：
 * <ul>
 *   <li>RED 阶段（Step 1c）：PersonProfileService 类不存在 → 编译失败 → 8 用例全 RED</li>
 *   <li>GREEN 阶段（Step 8）：Step 5-7 写完 ProfileDataSchema + PersonProfileService → 全 PASS</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("classpath:db/v6-test-setup.sql") // 预置 family=1 + user=1 等基础数据（person_profiles FK 引用）
class ProfilePatchTest {

    @Autowired
    private PersonProfileService profileService;

    /** 直调 Service 不走 HTTP → 无 TestUserContextFilter，显式注入 OWNER 上下文 */
    @BeforeEach
    void setUp() {
        UserContext.setCurrent(new UserContext.CurrentUser(1L, "test@test.com", "test", 1L, "OWNER"));
    }
    @AfterEach
    void tearDown() { UserContext.clear(); }

    /** 测试辅助：建一个 BABY 档案（默认家庭 1，OWNER），返回 profileId */
    private Long createBabyProfileWith(Map<String, Object> initialData) {
        // 通过 Service 层 create 建 BABY 档案，简化测试前置依赖
        // 测试时直接调用 profileService.create 等价于 P-2 POST 但不走 MockMvc
        var req = new com.familyapp.profile.dto.CreatePersonProfileRequest();
        req.setProfileType("BABY");
        req.setName("测试宝宝");
        req.setRelation("儿子");
        req.setProfileData(initialData);
        var response = profileService.create(req);
        return response.id();
    }

    // ==================== 空 patch 行为（4 用例） ====================

    @Test
    @DisplayName("1. 空 patch + 空 removeKeys → discardedKeys 空数组 + profile_data 无变化")
    void 空patch_空removeKeys_无变化() {
        Long id = createBabyProfileWith(Map.of("month_age", 6));

        var result = profileService.applyProfilePatch(1L, id, Map.of(), List.of());

        assertTrue(result.discardedKeys().isEmpty(), "空 patch 不应有任何 discard");
        assertEquals(6, result.mergedProfileData().get("month_age"), "原值应保留");
    }

    // ==================== 合法 patch 行为（2 用例） ====================

    @Test
    @DisplayName("2. 合法单 key patch（BABY.month_age=9）→ merge 成功 + discardedKeys 空")
    void 合法单key_patch_合并成功() {
        Long id = createBabyProfileWith(Map.of("month_age", 6));

        var result = profileService.applyProfilePatch(1L, id, Map.of("month_age", 9), List.of());

        assertEquals(9, result.mergedProfileData().get("month_age"), "新值应覆盖");
        assertTrue(result.discardedKeys().isEmpty(), "合法 key 不应有 discard");
    }

    @Test
    @DisplayName("3. 合法多 key patch（month_age + allergy_list）→ 全 merge 成功")
    void 合法多key_patch_全合并成功() {
        Long id = createBabyProfileWith(Map.of("month_age", 6));

        var result = profileService.applyProfilePatch(1L, id,
            Map.of("month_age", 12, "allergy_list", List.of("蛋白")),
            List.of());

        assertEquals(12, result.mergedProfileData().get("month_age"));
        assertEquals(List.of("蛋白"), result.mergedProfileData().get("allergy_list"));
        assertTrue(result.discardedKeys().isEmpty());
    }

    // ==================== 非法 patch 行为（3 用例） ====================

    @Test
    @DisplayName("4. 非法 key patch（BABY.unknown_key）→ discard + profile_data 不变 + 业务流程不阻断")
    void 非法key_patch_丢弃不阻断() {
        Long id = createBabyProfileWith(Map.of("month_age", 6));

        var result = profileService.applyProfilePatch(1L, id,
            Map.of("unknown_key", "xxx"),
            List.of());

        assertFalse(result.mergedProfileData().containsKey("unknown_key"),
            "非法 key 不应进 profile_data");
        assertEquals(1, result.discardedKeys().size(), "应有 1 条 discard");
        assertEquals("unknown_key", result.discardedKeys().get(0).key());
        assertEquals(6, result.mergedProfileData().get("month_age"),
            "原值应保留，业务流程不阻断");
    }

    @Test
    @DisplayName("5. 非法类型 patch（BABY.month_age=\"abc\"）→ discard + 不阻断")
    void 非法类型_patch_丢弃不阻断() {
        Long id = createBabyProfileWith(Map.of("month_age", 6));

        var result = profileService.applyProfilePatch(1L, id,
            Map.of("month_age", "abc"),
            List.of());

        assertEquals(6, result.mergedProfileData().get("month_age"),
            "类型错时原值应保留");
        assertEquals(1, result.discardedKeys().size());
        assertEquals("month_age", result.discardedKeys().get(0).key());
    }

    @Test
    @DisplayName("6. 部分合法部分非法 patch → 合法 merge + 非法 discard")
    void 部分合法部分非法_混合处理() {
        Long id = createBabyProfileWith(Map.of("month_age", 6));

        var result = profileService.applyProfilePatch(1L, id,
            Map.of(
                "month_age", 9,                    // 合法 int 在范围
                "allergy_list", List.of("蛋白"),   // 合法 string[]
                "unknown_key", "xxx",              // 非法 key
                "month_age_dup", "abc"             // 非法 key
            ),
            List.of());

        assertEquals(9, result.mergedProfileData().get("month_age"), "合法 key 应 merge");
        assertEquals(List.of("蛋白"), result.mergedProfileData().get("allergy_list"));
        assertFalse(result.mergedProfileData().containsKey("unknown_key"));
        assertFalse(result.mergedProfileData().containsKey("month_age_dup"));
        assertEquals(2, result.discardedKeys().size(), "应有 2 条 discard（unknown_key + month_age_dup）");
    }

    // ==================== remove_keys 行为（2 用例） ====================

    @Test
    @DisplayName("7. remove_keys 删除已存在 key → 200 + key 被删")
    void removeKeys_删除已存在key() {
        Long id = createBabyProfileWith(Map.of("month_age", 6, "allergy_list", List.of("牛奶")));

        var result = profileService.applyProfilePatch(1L, id, Map.of(), List.of("allergy_list"));

        assertFalse(result.mergedProfileData().containsKey("allergy_list"),
            "remove_keys 列表中的 key 应被删除");
        assertEquals(6, result.mergedProfileData().get("month_age"),
            "未在 remove_keys 中的 key 应保留");
        assertTrue(result.discardedKeys().isEmpty(), "remove 不产生 discard");
    }

    @Test
    @DisplayName("8. patch + remove_keys 同 key → 先 merge 再 remove（最终 key 被删）+ remove 不存在 key 幂等")
    void patch_同key_removeKeys_先合并再删除() {
        Long id = createBabyProfileWith(Map.of("month_age", 6));

        // month_age 先 patch=9 再 remove → 最终被删
        // not_exist_key 在 remove_keys 但 profile_data 没有 → 幂等不报错
        var result = profileService.applyProfilePatch(1L, id,
            Map.of("month_age", 9),
            List.of("month_age", "not_exist_key"));

        assertFalse(result.mergedProfileData().containsKey("month_age"),
            "patch 后再 remove 的 key 应最终被删");
        assertFalse(result.mergedProfileData().containsKey("not_exist_key"),
            "不存在的 key remove 应幂等无变化");
        assertTrue(result.discardedKeys().isEmpty(),
            "remove_keys 不产生 discard（即使 key 不存在）");
    }
}
