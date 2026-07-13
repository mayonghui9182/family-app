package com.family.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 宝宝相册VO
 */
@Data
public class BabyAlbumVO {

    /**
     * 相册ID
     */
    private Long id;

    /**
     * 宝宝ID
     */
    private Long babyId;

    /**
     * 家庭ID
     */
    private Long familyId;

    /**
     * 标题
     */
    private String title;

    /**
     * 描述
     */
    private String description;

    /**
     * 封面URL
     */
    private String coverUrl;

    /**
     * 照片数量
     */
    private Integer photoCount;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
