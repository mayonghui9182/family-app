package com.familyapp.family.dto;

import jakarta.validation.constraints.Size;

/**
 * 更新家庭信息请求（所有字段可选，仅传需要修改的）。
 */
public class UpdateFamilyRequest {

    @Size(max = 50, message = "家庭名称不能超过 50 字符")
    private String name;

    @Size(max = 200, message = "家庭描述不能超过 200 字符")
    private String description;

    private String avatar;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
}