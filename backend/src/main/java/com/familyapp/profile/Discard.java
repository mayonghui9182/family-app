package com.familyapp.profile;

/**
 * profile_data patch 校验丢弃记录
 * @param key 被丢弃的 key 名
 * @param reason 丢弃原因（key_not_in_whitelist / type_mismatch_expect_int / range_exceeded / enum_invalid_value 等）
 * 用于 ProfileDataSchema.validatePatch 返回的 discardedKeys 列表
 */
public record Discard(String key, String reason) {}
