package com.family.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推送设备实体类
 */
@Data
@TableName("push_device")
public class PushDevice {

    /**
     * 设备ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 家庭ID
     */
    private Long familyId;

    /**
     * 设备推送token
     */
    private String deviceToken;

    /**
     * 设备类型（ios/android/web）
     */
    private String deviceType;

    /**
     * 设备名称
     */
    private String deviceName;

    /**
     * 是否启用（0-禁用，1-启用）
     */
    private Integer isEnabled;

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
