package com.family.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.family.entity.BabyVaccine;
import org.apache.ibatis.annotations.Mapper;

/**
 * 宝宝疫苗接种记录 Mapper 接口
 */
@Mapper
public interface BabyVaccineMapper extends BaseMapper<BabyVaccine> {

}
