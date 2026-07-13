package com.family.vo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 成长里程碑VO
 */
@Data
public class MilestoneVO {

    /**
     * 里程碑ID
     */
    private Long id;

    /**
     * 宝宝ID
     */
    private Long babyId;

    /**
     * 月龄
     */
    private Integer monthAge;

    /**
     * 分类（motor/fine_motor/language/social）
     */
    private String category;

    /**
     * 标题
     */
    private String title;

    /**
     * 描述
     */
    private String description;

    /**
     * 标准月龄
     */
    private Integer standardMonth;

    /**
     * 状态（pending/achieved/concerned）
     */
    private String status;

    /**
     * 达成日期
     */
    private LocalDate achievedDate;

    /**
     * 备注
     */
    private String note;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
