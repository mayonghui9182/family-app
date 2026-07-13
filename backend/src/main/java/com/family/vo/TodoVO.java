package com.family.vo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 待办事项VO
 */
@Data
public class TodoVO {

    /**
     * 待办ID
     */
    private Long id;

    /**
     * 家庭ID
     */
    private Long familyId;

    /**
     * 标题
     */
    private String title;

    /**
     * 内容类型（text/voice）
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
     * 是否完成
     */
    private Integer completed;

    /**
     * 完成时间
     */
    private LocalDateTime completedTime;

    /**
     * 完成人ID
     */
    private Long completedBy;

    /**
     * 完成时间
     */
    private LocalDateTime completedAt;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建人ID
     */
    private Long createdBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
