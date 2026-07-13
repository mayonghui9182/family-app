package com.family.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 旅游攻略VO
 */
@Data
public class TravelGuideVO {

    /**
     * 攻略ID
     */
    private Long id;

    /**
     * 攻略标题
     */
    private String title;

    /**
     * 目的地
     */
    private String destination;

    /**
     * 出发日期
     */
    private LocalDate startDate;

    /**
     * 返回日期
     */
    private LocalDate endDate;

    /**
     * 天数
     */
    private Integer days;

    /**
     * 人数
     */
    private Integer peopleCount;

    /**
     * 预算
     */
    private BigDecimal budget;

    /**
     * 交通方式
     */
    private String transportation;

    /**
     * 住宿信息
     */
    private String accommodation;

    /**
     * 行程描述
     */
    private String itinerary;

    /**
     * 注意事项
     */
    private String notes;

    /**
     * 封面图片
     */
    private String coverImage;

    /**
     * 状态
     */
    private Integer status;

    /**
     * 亮点列表
     */
    private List<TravelHighlightVO> highlights;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
