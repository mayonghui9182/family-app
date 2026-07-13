package com.family.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 热门目的地VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HotDestinationVO {

    /**
     * 目的地名称
     */
    private String name;

    /**
     * 攻略数量
     */
    private Integer guideCount;

    /**
     * 封面图片
     */
    private String coverImage;

}
