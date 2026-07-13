package com.family.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.family.entity.Reminder;
import org.apache.ibatis.annotations.Mapper;

/**
 * 提醒事项 Mapper 接口
 */
@Mapper
public interface ReminderMapper extends BaseMapper<Reminder> {

}
