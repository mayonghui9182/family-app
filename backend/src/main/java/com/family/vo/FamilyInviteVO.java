package com.family.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 家庭邀请VO
 */
@Data
public class FamilyInviteVO {

    /**
     * 邀请ID
     */
    private Long id;

    /**
     * 邀请码
     */
    private String inviteCode;

    /**
     * 邀请人ID
     */
    private Long inviterId;

    /**
     * 邀请人名称
     */
    private String inviterName;

    /**
     * 最大使用次数
     */
    private Integer maxCount;

    /**
     * 已使用次数
     */
    private Integer useCount;

    /**
     * 状态
     */
    private String status;

    /**
     * 过期时间
     */
    private LocalDateTime expireTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
