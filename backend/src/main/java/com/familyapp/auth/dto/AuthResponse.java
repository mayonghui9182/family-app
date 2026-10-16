package com.familyapp.auth.dto;

/**
 * 注册成功响应。同时返回 accessToken（注册即登录，老人免二次操作）。
 */
public record AuthResponse(
    String accessToken,
    Long userId,
    String email,
    String nickname,
    Long familyId
) {}