package com.family.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.family.entity.Vaccine;
import org.apache.ibatis.annotations.Mapper;

/**
 * 疫苗 Mapper 接口
 */
@Mapper
public interface VaccineMapper extends BaseMapper<Vaccine> {

}
