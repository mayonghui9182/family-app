package com.family.vo;

import lombok.Data;

/**
 * 登录返回VO
 */
@Data
public class LoginVO {

    /**
     * Token
     */
    private String token;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户名
     */
    private String userName;

    /**
     * 家庭ID
     */
    private Long familyId;

    /**
     * 家庭名称
     */
    private String familyName;

    /**
     * 邀请码
     */
    private String inviteCode;

    /**
     * 角色
     */
    private String role;

}
