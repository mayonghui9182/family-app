package com.familyapp.auth.dto;

import jakarta.validation.constraints.Size;

/**
 * 修改密码请求。
 * <p>oldPassword 用于身份二次确认；newPassword 长度 8-64 与注册同口径。
 * 新密码不能与旧密码一致（防误操作）。</p>
 */
public class ChangePasswordRequest {

    @Size(min = 1, max = 64, message = "旧密码长度不正确")
    private String oldPassword;

    @Size(min = 8, max = 64, message = "新密码长度需要 8 到 64 位")
    private String newPassword;

    public String getOldPassword() { return oldPassword; }
    public void setOldPassword(String oldPassword) { this.oldPassword = oldPassword; }
    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
}
