package com.family.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.family.entity.WeatherRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 天气记录 Mapper 接口
 */
@Mapper
public interface WeatherRecordMapper extends BaseMapper<WeatherRecord> {

}
