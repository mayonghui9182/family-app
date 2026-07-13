package com.family.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 物品统计VO
 */
@Data
public class ItemStatsVO {

    /**
     * 物品总数
     */
    private Long totalItems;

    /**
     * 低库存物品数
     */
    private Long lowStockItems;

    /**
     * 分类统计列表
     */
    private List<CategoryStats> categories;

    /**
     * 本月入库量
     */
    private BigDecimal monthlyIn;

    /**
     * 本月出库量
     */
    private BigDecimal monthlyOut;

    /**
     * 分类统计
     */
    @Data
    public static class CategoryStats {
        private String category;
        private String categoryName;
        private Long count;
    }

}
