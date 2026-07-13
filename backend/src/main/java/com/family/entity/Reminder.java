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
 * 提醒事项实体类
 */
@Data
@TableName("reminder")
public class Reminder {

    /**
     * 提醒ID
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
     * 分类
     */
    private String category;

    /**
     * 关联待办ID
     */
    private Long todoId;

    /**
     * 提醒标题
     */
    private String title;

    /**
     * 提醒内容
     */
    private String content;

    /**
     * 提醒类型（生日、纪念日、缴费等）
     */
    private String type;

    /**
     * 时间类型（absolute/relative）
     */
    private String timeType = "absolute";

    /**
     * 相对时间（分钟）
     */
    private Integer relativeMinutes = 0;

    /**
     * 重复配置
     */
    private String repeatConfig;

    /**
     * 重复类型（none、daily、weekly、monthly、yearly）
     */
    private String repeatType;

    /**
     * 提醒时间点
     */
    private LocalTime remindTime;

    /**
     * 是否启用（0-禁用，1-启用）
     */
    private Integer enabled;

    /**
     * 是否已提醒（0-未提醒，1-已提醒）
     */
    private Integer reminded;

    /**
     * 今日是否已完成（0-否，1-是）
     */
    private Integer completedToday = 0;

    /**
     * 下次提醒日期
     */
    private LocalDate nextRemindDate;

    /**
     * 下次提醒时间
     */
    private LocalDateTime nextRemindAt;

    /**
     * 已提醒次数
     */
    private Integer remindCount = 0;

    /**
     * 最大提醒次数
     */
    private Integer maxRemindCount = 3;

    /**
     * 稍后提醒分钟数
     */
    private Integer snoozeMinutes = 5;

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
     * 最后触发时间
     */
    private LocalDateTime lastTriggeredAt;

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
