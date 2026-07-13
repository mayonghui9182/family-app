package com.family.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 成员信息VO
 */
@Data
public class MemberVO {

    /**
     * 用户ID
     */
    private Long id;

    /**
     * 用户名
     */
    private String username;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 头像
     */
    private String avatar;

    /**
     * 角色
     */
    private String role;

    /**
     * 头像颜色
     */
    private String avatarColor;

    /**
     * 状态（0-禁用，1-启用）
     */
    private Integer status;

    /**
     * 最后登录时间
     */
    private LocalDateTime lastLoginTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
