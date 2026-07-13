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
 * 物品出入库记录实体类
 */
@Data
@TableName("item_record")
public class ItemRecord {

    /**
     * 记录ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 家庭ID
     */
    private Long familyId;

    /**
     * 物品ID
     */
    private Long itemId;

    /**
     * 类型（in-入库，out-出库）
     */
    private String type;

    /**
     * 数量
     */
    private BigDecimal quantity;

    /**
     * 记录日期
     */
    private LocalDate recordDate;

    /**
     * 操作人ID
     */
    private Long operatorId;

    /**
     * 来源/用途（购买、使用、赠送等）
     */
    private String source;

    /**
     * 单价（元）
     */
    private BigDecimal price;

    /**
     * 备注
     */
    private String remark;

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
