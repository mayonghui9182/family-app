package com.family.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 家庭物品VO
 */
@Data
public class HouseholdItemVO {

    /**
     * 物品ID
     */
    private Long id;

    /**
     * 物品名称
     */
    private String name;

    /**
     * 分类
     */
    private String category;

    /**
     * 分类名称
     */
    private String categoryName;

    /**
     * 单位
     */
    private String unit;

    /**
     * 当前库存数量
     */
    private BigDecimal totalQuantity;

    /**
     * 预警数量
     */
    private BigDecimal warningQuantity;

    /**
     * 图标/表情
     */
    private String icon;

    /**
     * 备注
     */
    private String remark;

    /**
     * 是否低库存
     */
    private Boolean isLowStock;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
