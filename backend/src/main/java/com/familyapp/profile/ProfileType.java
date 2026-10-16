package com.familyapp.profile;

/**
 * 人员档案类型枚举（5 值，与 V9.sql CHECK 约束一致）
 * BABY 宝宝 / ELDER 老人 / ADULT 成人 / PET 宠物 / OTHER 其他
 * 用于 person_profiles.profile_type 列 + profile-schema.json 白名单分组
 */
public enum ProfileType {
    BABY, ELDER, ADULT, PET, OTHER
}
