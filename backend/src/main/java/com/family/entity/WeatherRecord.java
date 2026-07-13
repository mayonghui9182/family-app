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
 * 天气记录实体类
 */
@Data
@TableName("weather_record")
public class WeatherRecord {

    /**
     * 记录ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 城市ID
     */
    private Long cityId;

    /**
     * 记录日期
     */
    private LocalDate recordDate;

    /**
     * 天气类型（晴、多云、雨等）
     */
    private String weatherType;

    /**
     * 天气图标
     */
    private String weatherIcon;

    /**
     * 当前温度
     */
    private BigDecimal temperature;

    /**
     * 最低温度
     */
    private BigDecimal tempMin;

    /**
     * 最高温度
     */
    private BigDecimal tempMax;

    /**
     * 体感温度
     */
    private BigDecimal feelsLike;

    /**
     * 湿度（%）
     */
    private Integer humidity;

    /**
     * 风力
     */
    private String windPower;

    /**
     * 风向
     */
    private String windDirection;

    /**
     * 空气质量
     */
    private String airQuality;

    /**
     * 空气质量指数
     */
    private Integer aqi;

    /**
     * 紫外线强度
     */
    private String uvIndex;

    /**
     * 能见度（km）
     */
    private BigDecimal visibility;

    /**
     * 气压（hPa）
     */
    private Integer pressure;

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
