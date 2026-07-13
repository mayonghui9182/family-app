package com.family.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 加入家庭组DTO
 */
@Data
public class JoinFamilyDTO {

    /**
     * 邀请码
     */
    @NotBlank(message = "邀请码不能为空")
    private String inviteCode;

    /**
     * 用户名
     */
    @NotBlank(message = "用户名不能为空")
    private String userName;

}
