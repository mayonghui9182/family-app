package com.family.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.family.entity.ItemRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 物品出入库记录 Mapper 接口
 */
@Mapper
public interface ItemRecordMapper extends BaseMapper<ItemRecord> {

}
