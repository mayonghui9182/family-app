package com.familyapp.profile;

import java.util.List;
import java.util.Map;

/**
 * profile_data patch 校验结果
 * @param validPatch 通过校验的合法 key-value（可直接用于 JSONB merge）
 * @param discardedKeys 被丢弃的 key 列表（含原因），不阻断业务流程
 * 用于 ProfileDataSchema.validatePatch 返回值
 */
public record ValidationResult(Map<String, Object> validPatch, List<Discard> discardedKeys) {}
