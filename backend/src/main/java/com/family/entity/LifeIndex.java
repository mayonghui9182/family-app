package com.family.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 生活指数实体类
 */
@Data
@TableName("life_index")
public class LifeIndex {

    /**
     * 指数ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 城市ID
     */
    private Long cityId;

    /**
     * 指数日期
     */
    private LocalDate indexDate;

    /**
     * 指数类型（穿衣、紫外线、运动、洗车等）
     */
    private String indexType;

    /**
     * 指数名称
     */
    private String indexName;

    /**
     * 指数等级
     */
    private String level;

    /**
     * 指数等级描述
     */
    private String levelDesc;

    /**
     * 建议
     */
    private String suggestion;

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
