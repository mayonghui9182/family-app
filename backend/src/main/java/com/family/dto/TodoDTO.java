package com.family.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 待办事项DTO
 */
@Data
public class TodoDTO {

    /**
     * 标题
     */
    private String title;

    /**
     * 内容类型
     */
    private String contentType;

    /**
     * 语音文件URL
     */
    private String voiceUrl;

    /**
     * 语音时长（秒）
     */
    private Integer voiceDuration;

    /**
     * 待办内容
     */
    private String content;

    /**
     * 分类
     */
    private String category;

    /**
     * 优先级
     */
    private String priority;

    /**
     * 截止时间
     */
    private LocalDateTime deadline;

    /**
     * 截止日期
     */
    private LocalDate dueDate;

    /**
     * 截止时间
     */
    private LocalTime dueTime;

    /**
     * 是否有提醒
     */
    private Boolean hasReminder;

    /**
     * 提醒时间类型
     */
    private String remindTimeType;

    /**
     * 提醒相对时间（分钟）
     */
    private Integer remindRelativeMinutes;

    /**
     * 重复类型
     */
    private String repeatType;

    /**
     * 重复配置
     */
    private String repeatConfig;

    /**
     * 备注
     */
    private String remark;

}
