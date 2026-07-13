package com.family.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 当前天气VO
 */
@Data
public class WeatherCurrentVO {

    /**
     * 城市名称
     */
    private String cityName;

    /**
     * 记录日期
     */
    private LocalDate recordDate;

    /**
     * 天气类型
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

}
