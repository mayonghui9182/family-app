package com.family.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.family.entity.Todo;
import org.apache.ibatis.annotations.Mapper;

/**
 * 待办事项 Mapper 接口
 */
@Mapper
public interface TodoMapper extends BaseMapper<Todo> {

}
