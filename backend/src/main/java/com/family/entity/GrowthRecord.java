package com.family.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 成长记录实体类
 */
@Data
@TableName("growth_record")
public class GrowthRecord {

    /**
     * 记录ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 宝宝ID
     */
    private Long babyId;

    /**
     * 记录类型
     */
    private String recordType;

    /**
     * 记录日期
     */
    private LocalDate recordDate;

    /**
     * 身高（厘米）
     */
    private BigDecimal height;

    /**
     * 体重（千克）
     */
    private BigDecimal weight;

    /**
     * 餐次类型
     */
    private String mealType;

    /**
     * 食物描述
     */
    private String foodDesc;

    /**
     * 食量
     */
    private BigDecimal amount;

    /**
     * 睡眠时长（分钟）
     */
    private Integer sleepDuration;

    /**
     * 日常记录内容
     */
    private String content;

    /**
     * 备注
     */
    private String note;

    /**
     * 记录人ID
     */
    private Long recordedBy;

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
