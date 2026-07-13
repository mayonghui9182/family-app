package com.family.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推送设备VO
 */
@Data
public class PushDeviceVO {

    /**
     * 设备ID
     */
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
     * 是否启用
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

}
