package com.family.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 成长记录DTO
 */
@Data
public class GrowthRecordDTO {

    /**
     * 记录类型
     */
    private String recordType;

    /**
     * 记录日期
     */
    @NotNull(message = "记录日期不能为空")
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

}
