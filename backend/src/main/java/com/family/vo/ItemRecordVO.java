package com.family.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 物品出入库记录VO
 */
@Data
public class ItemRecordVO {

    /**
     * 记录ID
     */
    private Long id;

    /**
     * 物品ID
     */
    private Long itemId;

    /**
     * 物品名称
     */
    private String itemName;

    /**
     * 类型（in-入库，out-出库）
     */
    private String type;

    /**
     * 类型名称
     */
    private String typeName;

    /**
     * 数量
     */
    private BigDecimal quantity;

    /**
     * 单位
     */
    private String unit;

    /**
     * 记录日期
     */
    private LocalDate recordDate;

    /**
     * 操作人ID
     */
    private Long operatorId;

    /**
     * 操作人名称
     */
    private String operatorName;

    /**
     * 来源/用途
     */
    private String source;

    /**
     * 单价（元）
     */
    private BigDecimal price;

    /**
     * 总价（price * quantity）
     */
    private BigDecimal totalPrice;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
