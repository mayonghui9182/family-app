package com.family.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 待办事项实体类
 */
@Data
@TableName("todo")
public class Todo {

    /**
     * 待办ID
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
     * 标题
     */
    private String title;

    /**
     * 内容类型（text/voice）
     */
    private String contentType = "text";

    /**
     * 语音文件URL
     */
    private String voiceUrl;

    /**
     * 语音时长（秒）
     */
    private Integer voiceDuration = 0;

    /**
     * 待办内容
     */
    private String content;

    /**
     * 分类（工作、生活、学习等）
     */
    private String category;

    /**
     * 优先级（low、medium、high）
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
     * 是否完成（0-未完成，1-已完成）
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
     * 排序
     */
    private Integer sortOrder;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 创建人ID
     */
    private Long createdBy;

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
