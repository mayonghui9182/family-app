package com.family.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.family.entity.WeatherCity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 天气城市 Mapper 接口
 */
@Mapper
public interface WeatherCityMapper extends BaseMapper<WeatherCity> {

}
