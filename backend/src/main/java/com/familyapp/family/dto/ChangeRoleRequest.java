package com.familyapp.family.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 修改成员角色请求（OWNER 专属，ADULT 与 CHILD 互转）。
 */
public class ChangeRoleRequest {

    @NotBlank(message = "角色不能为空")
    private String role;

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}