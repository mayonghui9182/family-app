package com.family.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 旅游亮点VO
 */
@Data
public class TravelHighlightVO {

    /**
     * 亮点ID
     */
    private Long id;

    /**
     * 亮点标题
     */
    private String title;

    /**
     * 亮点描述
     */
    private String description;

    /**
     * 图片URL
     */
    private String imageUrl;

    /**
     * 地点
     */
    private String location;

    /**
     * 建议游玩时长（小时）
     */
    private BigDecimal duration;

    /**
     * 排序
     */
    private Integer sortOrder;

}
