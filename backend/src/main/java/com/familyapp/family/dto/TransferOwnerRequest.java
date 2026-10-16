package com.familyapp.family.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 转让管理员请求。targetUserId 必须是家庭内当前活跃的成人或孩子成员。
 */
public class TransferOwnerRequest {

    @NotNull(message = "请指定新管理员")
    private Long targetUserId;

    public Long getTargetUserId() { return targetUserId; }
    public void setTargetUserId(Long targetUserId) { this.targetUserId = targetUserId; }
}