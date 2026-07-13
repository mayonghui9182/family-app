package com.family.vo;

import lombok.Data;

/**
 * 生活指数VO
 */
@Data
public class LifeIndexVO {

    /**
     * 指数类型
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

}
