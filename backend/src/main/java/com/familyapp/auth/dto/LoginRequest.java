package com.familyapp.auth.dto;

import jakarta.validation.constraints.Size;

/**
 * 登录请求。email 和 phone 二选一。
 */
public class LoginRequest {

    @Size(max = 120, message = "邮箱长度不能超过 120 字符")
    private String email;

    @Size(max = 20, message = "手机号长度不能超过 20 字符")
    private String phone;

    @Size(min = 1, message = "密码不能为空")
    private String password;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}