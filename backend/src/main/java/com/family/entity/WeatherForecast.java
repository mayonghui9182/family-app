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
 * 天气预报实体类
 */
@Data
@TableName("weather_forecast")
public class WeatherForecast {

    /**
     * 预报ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 城市ID
     */
    private Long cityId;

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
