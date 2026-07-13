package com.family.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提醒事项DTO
 */
@Data
public class ReminderDTO {

    /**
     * 提醒标题
     */
    @NotBlank(message = "提醒标题不能为空")
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
     * 备注
     */
    private String remark;

}
