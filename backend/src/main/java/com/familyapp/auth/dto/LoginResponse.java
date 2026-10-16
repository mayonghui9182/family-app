package com.familyapp.auth.dto;

import com.familyapp.family.dto.FamilySummaryResponse;
import java.util.List;

/**
 * 登录成功响应。families[] 列出用户当前所有活跃家庭及角色。
 * roleName 为中文角色名（家庭管理员/成人成员/孩子成员），适配老人显示。
 * userId 用于前端「本人待办/本人逾期」筛选与首页统计卡片跳转。
 */
public record LoginResponse(
    String accessToken,
    Long userId,
    String nickname,
    List<FamilySummaryResponse> families
) {}