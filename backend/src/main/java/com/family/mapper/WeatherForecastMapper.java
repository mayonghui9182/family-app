package com.family.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.family.entity.WeatherForecast;
import org.apache.ibatis.annotations.Mapper;

/**
 * 天气预报 Mapper 接口
 */
@Mapper
public interface WeatherForecastMapper extends BaseMapper<WeatherForecast> {

}
