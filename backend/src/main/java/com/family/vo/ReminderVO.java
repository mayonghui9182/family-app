package com.family.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提醒事项VO
 */
@Data
public class ReminderVO {

    /**
     * 提醒ID
     */
    private Long id;

    /**
     * 提醒标题
     */
    private String title;

    /**
     * 提醒内容
     */
    private String content;

    /**
     * 提醒类型
     */
    private String type;

    /**
     * 提醒时间
     */
    private LocalDateTime remindTime;

    /**
     * 重复类型
     */
    private String repeatType;

    /**
     * 是否启用
     */
    private Integer enabled;

    /**
     * 是否已提醒
     */
    private Integer reminded;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
