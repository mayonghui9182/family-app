package com.familyapp.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * profile_data JSONB 白名单校验逻辑测试（B1 精简版 5 类型）
 *
 * <p>验证对象：ProfileDataSchema.validatePatch(String profileType, Map<String, Object> patch)
 *
 * <p>设计依据（spec §1.2 + plan Step 2 profile-schema.json SSOT）：
 * <ol>
 *   <li>4 类合法 key × 非法 key × 非法类型 × 超范围值 全覆盖</li>
 *   <li>BABY 白名单无 diet_restrictions（ELDER 有）→ cross-type 误传应丢弃</li>
 *   <li>OTHER.unknown_key → 任意类型都应丢弃</li>
 *   <li>month_age="abc"（字符串冒充 int）→ 类型不匹配丢弃</li>
 *   <li>month_age=300（超 [0,240] 范围）→ 范围不匹配丢弃</li>
 * </ol>
 *
 * <p>校验策略（spec §1.2 + plan §B 核心决策 1）：
 * <ul>
 *   <li>每个 patch key 若不在该 profile_type 白名单 → 丢弃，记入 discarded_keys[] + WARN 日志（不阻断）</li>
 *   <li>值类型不匹配 → 同样丢弃，不阻断</li>
 *   <li>int 类型的 range 超界 → 丢弃</li>
 *   <li>enum 类型的 values 不在枚举内 → 丢弃</li>
 *   <li>JSONB merge 策略 = 顶层浅合并 existing || new_patch；删除 key 走 P-4 remove_keys[] 字段</li>
 * </ul>
 *
 * <p>API 设计（plan Step 5 §ProfileDataSchema.java）：
 * <pre>
 *   class ProfileDataSchema {
 *     ProfileDataSchema() { loadFromClasspath("schemas/profile-schema.json"); }
 *     ValidationResult validatePatch(String profileType, Map&lt;String, Object&gt; patch);
 *   }
 *   record ValidationResult(Map&lt;String, Object&gt; validPatch, List&lt;Discard&gt; discardedKeys) {}
 *   record Discard(String key, String reason) {}
 * </pre>
 *
 * <p>TDD 状态：
 * <ul>
 *   <li>RED 阶段（Step 1c）：ProfileDataSchema 类不存在 → 编译失败 → 12 用例全 RED</li>
 *   <li>GREEN 阶段（Step 8）：Step 2 写 profile-schema.json + Step 5 写 ProfileDataSchema → 全 PASS</li>
 * </ul>
 */
class ProfileSchemaTest {

    /** 被测对象：加载 classpath:schemas/profile-schema.json 的白名单 schema */
    private final ProfileDataSchema schema = new ProfileDataSchema();

    // ==================== BABY 类型（10 key） ====================

    @Test
    @DisplayName("1. BABY.month_age=6 合法 int 在范围 [0,240] → validPatch 含此 key，discardedKeys 空")
    void baby合法monthAge_6_应通过校验() {
        ValidationResult result = schema.validatePatch("BABY", Map.of("month_age", 6));

        assertEquals(6, result.validPatch().get("month_age"), "合法 month_age=6 应进入 validPatch");
        assertTrue(result.discardedKeys().isEmpty(), "合法 key 不应有任何 discard");
    }

    @Test
    @DisplayName("2. BABY.month_age=\"abc\" 类型错（应 int 实际 string）→ discard，reason 含 type")
    void baby非法类型monthAge_字符串_应丢弃() {
        ValidationResult result = schema.validatePatch("BABY", Map.of("month_age", "abc"));

        assertFalse(result.validPatch().containsKey("month_age"), "类型错的 month_age 不应进 validPatch");
        assertEquals(1, result.discardedKeys().size(), "应有 1 条 discard 记录");
        assertEquals("month_age", result.discardedKeys().get(0).key(), "discard 的 key 应为 month_age");
        assertTrue(result.discardedKeys().get(0).reason().toLowerCase().contains("type"),
            "reason 应说明类型不匹配");
    }

    @Test
    @DisplayName("3. BABY.month_age=300 超出范围 [0,240] → discard，reason 含 range")
    void baby超范围monthAge_300_应丢弃() {
        ValidationResult result = schema.validatePatch("BABY", Map.of("month_age", 300));

        assertFalse(result.validPatch().containsKey("month_age"), "超范围 month_age 不应进 validPatch");
        assertEquals(1, result.discardedKeys().size(), "应有 1 条 discard 记录");
        assertTrue(result.discardedKeys().get(0).reason().toLowerCase().contains("range"),
            "reason 应说明范围超界");
    }

    @Test
    @DisplayName("4. BABY.allergy_list=[牛奶,蛋白] 合法 string[] → validPatch 含此 key")
    void baby合法allergyList_字符串数组_应通过() {
        ValidationResult result = schema.validatePatch("BABY", Map.of("allergy_list", List.of("牛奶", "蛋白")));

        assertEquals(List.of("牛奶", "蛋白"), result.validPatch().get("allergy_list"),
            "合法 allergy_list 应原样进入 validPatch");
        assertTrue(result.discardedKeys().isEmpty(), "合法 key 不应有 discard");
    }

    @Test
    @DisplayName("5. BABY.diet_restrictions（ELDER 白名单有，BABY 无）→ discard 长度 1，跨类型误传拦截")
    void baby误传elder的diet_restrictions_应丢弃() {
        ValidationResult result = schema.validatePatch("BABY", Map.of("diet_restrictions", List.of("低盐")));

        assertFalse(result.validPatch().containsKey("diet_restrictions"),
            "BABY 白名单无 diet_restrictions，不应进 validPatch");
        assertEquals(1, result.discardedKeys().size(), "应有 1 条 discard 记录");
        assertEquals("diet_restrictions", result.discardedKeys().get(0).key(),
            "discard 的 key 应为 diet_restrictions");
    }

    // ==================== ELDER 类型（6 key） ====================

    @Test
    @DisplayName("6. ELDER.chronic_diseases=[高血压] 合法 string[] → validPatch 含此 key")
    void elder合法chronicDiseases_应通过() {
        ValidationResult result = schema.validatePatch("ELDER", Map.of("chronic_diseases", List.of("高血压")));

        assertEquals(List.of("高血压"), result.validPatch().get("chronic_diseases"),
            "合法 chronic_diseases 应进入 validPatch");
        assertTrue(result.discardedKeys().isEmpty(), "合法 key 不应有 discard");
    }

    @Test
    @DisplayName("7. ELDER.mobility=SELF 合法 enum 值 → validPatch 含此 key")
    void elder合法mobility_SELF_应通过() {
        ValidationResult result = schema.validatePatch("ELDER", Map.of("mobility", "SELF"));

        assertEquals("SELF", result.validPatch().get("mobility"), "合法 enum 值 SELF 应进入 validPatch");
        assertTrue(result.discardedKeys().isEmpty(), "合法 enum 值不应有 discard");
    }

    @Test
    @DisplayName("8. ELDER.mobility=UNKNOWN 非法 enum 值（不在 SELF/HALF/HELP）→ discard")
    void elder非法enum值_UNKNOWN_应丢弃() {
        ValidationResult result = schema.validatePatch("ELDER", Map.of("mobility", "UNKNOWN"));

        assertFalse(result.validPatch().containsKey("mobility"), "非法 enum 值不应进 validPatch");
        assertEquals(1, result.discardedKeys().size(), "应有 1 条 discard 记录");
        assertEquals("mobility", result.discardedKeys().get(0).key(), "discard 的 key 应为 mobility");
    }

    // ==================== ADULT 类型（6 key） ====================

    @Test
    @DisplayName("9. ADULT.work_start_time=\"09:00\" 合法 time → validPatch 含此 key")
    void adult合法workStartTime_应通过() {
        ValidationResult result = schema.validatePatch("ADULT", Map.of("work_start_time", "09:00"));

        assertEquals("09:00", result.validPatch().get("work_start_time"), "合法 time 应进入 validPatch");
        assertTrue(result.discardedKeys().isEmpty(), "合法 key 不应有 discard");
    }

    // ==================== PET 类型（6 key） ====================

    @Test
    @DisplayName("10. PET.age_years=3 合法 int 在范围 [0,80] → validPatch 含此 key")
    void pet合法ageYears_3_应通过() {
        ValidationResult result = schema.validatePatch("PET", Map.of("age_years", 3));

        assertEquals(3, result.validPatch().get("age_years"), "合法 age_years=3 应进入 validPatch");
        assertTrue(result.discardedKeys().isEmpty(), "合法 key 不应有 discard");
    }

    @Test
    @DisplayName("11. PET.age_years=-1 超出范围 [0,80] → discard，reason 含 range")
    void pet超范围ageYears_负一_应丢弃() {
        ValidationResult result = schema.validatePatch("PET", Map.of("age_years", -1));

        assertFalse(result.validPatch().containsKey("age_years"), "超范围 age_years 不应进 validPatch");
        assertEquals(1, result.discardedKeys().size(), "应有 1 条 discard 记录");
        assertTrue(result.discardedKeys().get(0).reason().toLowerCase().contains("range"),
            "reason 应说明范围超界");
    }

    // ==================== OTHER 类型（1 key notes） ====================

    @Test
    @DisplayName("12. OTHER.unknown_key=xxx 白名单无此 key → discard，验证兜底类型拦截能力")
    void other白名单无unknownKey_应丢弃() {
        ValidationResult result = schema.validatePatch("OTHER", Map.of("unknown_key", "xxx"));

        assertFalse(result.validPatch().containsKey("unknown_key"), "白名单外的 key 不应进 validPatch");
        assertEquals(1, result.discardedKeys().size(), "应有 1 条 discard 记录");
        assertEquals("unknown_key", result.discardedKeys().get(0).key(),
            "discard 的 key 应为 unknown_key");
    }
}
