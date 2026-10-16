package com.familyapp.family.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 接受邀请码请求。任何已登录用户都可调用，无需 X-Family-Id。
 */
public class AcceptInviteRequest {

    @NotBlank(message = "邀请码不能为空")
    @Size(min = 6, max = 6, message = "邀请码必须是 6 位")
    private String code;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
}