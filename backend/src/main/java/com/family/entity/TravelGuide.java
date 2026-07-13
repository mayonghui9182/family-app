package com.family.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 旅游攻略实体类
 */
@Data
@TableName("travel_guide")
public class TravelGuide {

    /**
     * 攻略ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 攻略标题
     */
    private String title;

    /**
     * 目的地
     */
    private String destination;

    /**
     * 出发日期
     */
    private LocalDate startDate;

    /**
     * 返回日期
     */
    private LocalDate endDate;

    /**
     * 天数
     */
    private Integer days;

    /**
     * 人数
     */
    private Integer peopleCount;

    /**
     * 预算
     */
    private java.math.BigDecimal budget;

    /**
     * 交通方式
     */
    private String transportation;

    /**
     * 住宿信息
     */
    private String accommodation;

    /**
     * 行程描述
     */
    private String itinerary;

    /**
     * 注意事项
     */
    private String notes;

    /**
     * 封面图片
     */
    private String coverImage;

    /**
     * 状态（0-草稿，1-已发布）
     */
    private Integer status;

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
