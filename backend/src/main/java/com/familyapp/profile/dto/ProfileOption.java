package com.familyapp.profile.dto;

/**
 * P-6 GET /options 下拉选项 DTO
 *
 * 设计依据：plan Step 5 + PersonProfileControllerTest P-6 验证 profileTypeName 中文
 * - profileType: 英文枚举值（BABY/ELDER/ADULT/PET/OTHER）
 * - profileTypeName: 中文名称（宝宝/老人/成人/宠物/其他）
 * - 用 record（不可变，方便 Controller 直接 new ProfileOption("BABY", "宝宝")）
 */
public record ProfileOption(String profileType, String profileTypeName) {}
