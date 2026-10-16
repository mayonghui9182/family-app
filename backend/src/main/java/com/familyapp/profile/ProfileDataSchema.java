package com.familyapp.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * profile_data JSONB 白名单校验器（SSOT: classpath:schemas/profile-schema.json）
 *
 * 设计依据：plan Step 5 + Global Constraints 第 3 条
 * - 加载 profile-schema.json 作为唯一真相源（4 类型 × 29 key）
 * - validatePatch 校验 patch 是否符合该 profileType 的白名单
 * - 超范围 key / 类型错 / range 超界 / enum 非法值 → 一律 discard + WARN 日志，不阻断流程
 * - 合法 key 进入 validPatch，可直接用于 JSONB merge（existing || validPatch）
 *
 * API（与 ProfileSchemaTest / ProfilePatchTest 匹配）：
 *   new ProfileDataSchema().validatePatch(String type, Map patch) → ValidationResult
 */
public class ProfileDataSchema {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Map<String, FieldSpec>> schema = new HashMap<>();

    public ProfileDataSchema() {
        try (InputStream is = new ClassPathResource("schemas/profile-schema.json").getInputStream()) {
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> raw = mapper.readValue(is, Map.class);
            for (Map.Entry<String, Map<String, Object>> typeEntry : raw.entrySet()) {
                Map<String, FieldSpec> fields = new HashMap<>();
                for (Map.Entry<String, Object> fieldEntry : typeEntry.getValue().entrySet()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> spec = (Map<String, Object>) fieldEntry.getValue();
                    @SuppressWarnings("unchecked")
                    List<Integer> range = spec.containsKey("range") ? (List<Integer>) spec.get("range") : null;
                    @SuppressWarnings("unchecked")
                    List<String> values = spec.containsKey("values") ? (List<String>) spec.get("values") : null;
                    fields.put(fieldEntry.getKey(), new FieldSpec((String) spec.get("type"), range, values));
                }
                schema.put(typeEntry.getKey(), fields);
            }
        } catch (Exception e) {
            throw new RuntimeException("加载 profile-schema.json 失败", e);
        }
    }

    /**
     * 校验 patch 是否符合该 profileType 的白名单
     * @param profileType 档案类型（BABY/ELDER/ADULT/PET/OTHER）
     * @param patch 待校验的 key-value 映射
     * @return ValidationResult（validPatch + discardedKeys）
     */
    public ValidationResult validatePatch(String profileType, Map<String, Object> patch) {
        Map<String, Object> validPatch = new HashMap<>();
        List<Discard> discardedKeys = new ArrayList<>();
        Map<String, FieldSpec> typeFields = schema.get(profileType);
        if (typeFields == null) {
            for (String key : patch.keySet()) {
                discardedKeys.add(new Discard(key, "type_unknown"));
            }
            return new ValidationResult(validPatch, discardedKeys);
        }
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            FieldSpec spec = typeFields.get(e.getKey());
            if (spec == null) {
                discardedKeys.add(new Discard(e.getKey(), "key_not_in_whitelist"));
                continue;
            }
            String err = spec.checkType(e.getValue());
            if (err != null) {
                discardedKeys.add(new Discard(e.getKey(), err));
                continue;
            }
            validPatch.put(e.getKey(), e.getValue());
        }
        return new ValidationResult(validPatch, discardedKeys);
    }

    /** 字段规格（type + range + values） */
    private static class FieldSpec {
        final String type;
        final List<Integer> range;
        final List<String> values;

        FieldSpec(String type, List<Integer> range, List<String> values) {
            this.type = type;
            this.range = range;
            this.values = values;
        }

        /** 返回 null=通过，非 null=错误原因 */
        String checkType(Object value) {
            switch (type) {
                case "number":
                    if (!(value instanceof Number)) {
                        return "type_mismatch_expect_number";
                    }
                    if (range != null) {
                        double v = ((Number) value).doubleValue();
                        if (v < range.get(0) || v > range.get(1)) {
                            return "range_exceeded";
                        }
                    }
                    return null;
                case "int":
                    if (!(value instanceof Integer) && !(value instanceof Long)) {
                        return "type_mismatch_expect_int";
                    }
                    if (range != null) {
                        long v = ((Number) value).longValue();
                        if (v < range.get(0) || v > range.get(1)) {
                            return "range_exceeded";
                        }
                    }
                    return null;
                case "string":
                    if (!(value instanceof String)) {
                        return "type_mismatch_expect_string";
                    }
                    return null;
                case "string[]":
                    if (!(value instanceof List)) {
                        return "type_mismatch_expect_string_array";
                    }
                    for (Object item : (List<?>) value) {
                        if (!(item instanceof String)) {
                            return "type_mismatch_array_item_not_string";
                        }
                    }
                    return null;
                case "enum":
                    if (!(value instanceof String)) {
                        return "type_mismatch_expect_enum_string";
                    }
                    if (values != null && !values.contains(value)) {
                        return "enum_invalid_value";
                    }
                    return null;
                case "time":
                case "date":
                    if (!(value instanceof String)) {
                        return "type_mismatch_expect_string";
                    }
                    return null;
                default:
                    return "type_unknown";
            }
        }
    }
}
