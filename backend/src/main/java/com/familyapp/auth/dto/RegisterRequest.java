package com.familyapp.auth.dto;

import jakarta.validation.constraints.Size;

/**
 * 注册请求。email 和 phone 至少填一个（由 Service 层校验 LOGIN_IDENTIFIER_REQUIRED）。
 */
public class RegisterRequest {

    @Size(max = 120, message = "邮箱长度不能超过 120 字符")
    private String email;

    @Size(max = 20, message = "手机号长度不能超过 20 字符")
    private String phone;

    @Size(max = 50, message = "昵称长度不能超过 50 字符")
    private String nickname;

    @Size(min = 8, max = 64, message = "密码长度需要 8 到 64 位")
    private String password;

    /** 家庭名称（可选，不填时默认为"昵称的家"）。 */
    private String familyName;

    /** 是否在注册时创建家庭；未传时沿用原有行为，默认创建。 */
    private Boolean createFamily;

    /** 选择加入已有家庭时填写的 6 位邀请码。 */
    @Size(min = 6, max = 6, message = "邀请码必须是 6 位")
    private String inviteCode;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getFamilyName() { return familyName; }
    public void setFamilyName(String familyName) { this.familyName = familyName; }
    public Boolean getCreateFamily() { return createFamily; }
    public void setCreateFamily(Boolean createFamily) { this.createFamily = createFamily; }
    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String inviteCode) {
        // 未选择邀请码加入时，兼容旧客户端发送的空字符串。
        this.inviteCode = inviteCode == null || inviteCode.isBlank() ? null : inviteCode.trim();
    }
}
