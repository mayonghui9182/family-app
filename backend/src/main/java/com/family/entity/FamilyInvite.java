package com.family.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 家庭邀请实体类
 */
@Data
@TableName("family_invite")
public class FamilyInvite {

    /**
     * 邀请ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 家庭ID
     */
    private Long familyId;

    /**
     * 邀请人ID
     */
    private Long inviterId;

    /**
     * 邀请码
     */
    private String inviteCode;

    /**
     * 过期时间
     */
    private LocalDateTime expireTime;

    /**
     * 已使用次数
     */
    private Integer useCount;

    /**
     * 最大使用次数
     */
    private Integer maxCount;

    /**
     * 状态（active-有效，expired-过期，disabled-禁用）
     */
    private String status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 逻辑删除（0-未删除，1-已删除）
     */
    @TableLogic
    @TableField(select = false)
    private Integer deleted;

}
