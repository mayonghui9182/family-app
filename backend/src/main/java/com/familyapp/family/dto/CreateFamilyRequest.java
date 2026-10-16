package com.familyapp.family.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建家庭请求。
 */
public class CreateFamilyRequest {

    @NotBlank(message = "家庭名称不能为空")
    @Size(max = 50, message = "家庭名称不能超过 50 字符")
    private String name;

    @Size(max = 200, message = "家庭描述不能超过 200 字符")
    private String description;

    private String avatar;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    /** 兼容测试别名：FamilyAutoInitOwnerProfileTest 调 setFamilyName；与 setName 等价 */
    public void setFamilyName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
}