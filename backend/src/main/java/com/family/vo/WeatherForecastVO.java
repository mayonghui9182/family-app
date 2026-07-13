package com.family.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 天气预报VO
 */
@Data
public class WeatherForecastVO {

    /**
     * 预报日期
     */
    private LocalDate forecastDate;

    /**
     * 星期几
     */
    private String weekday;

    /**
     * 白天天气类型
     */
    private String dayWeather;

    /**
     * 夜间天气类型
     */
    private String nightWeather;

    /**
     * 白天天气图标
     */
    private String dayIcon;

    /**
     * 夜间天气图标
     */
    private String nightIcon;

    /**
     * 最高温度
     */
    private BigDecimal tempHigh;

    /**
     * 最低温度
     */
    private BigDecimal tempLow;

    /**
     * 白天风力
     */
    private String dayWindPower;

    /**
     * 白天风向
     */
    private String dayWindDirection;

    /**
     * 夜间风力
     */
    private String nightWindPower;

    /**
     * 夜间风向
     */
    private String nightWindDirection;

    /**
     * 降水概率（%）
     */
    private Integer precipitationProbability;

    /**
     * 日出时间
     */
    private String sunrise;

    /**
     * 日落时间
     */
    private String sunset;

}
