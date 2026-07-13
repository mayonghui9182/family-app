package com.family.vo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 宝宝照片VO
 */
@Data
public class BabyPhotoVO {

    /**
     * 照片ID
     */
    private Long id;

    /**
     * 宝宝ID
     */
    private Long babyId;

    /**
     * 相册ID
     */
    private Long albumId;

    /**
     * 家庭ID
     */
    private Long familyId;

    /**
     * 照片URL
     */
    private String url;

    /**
     * 缩略图URL
     */
    private String thumbnailUrl;

    /**
     * 标题
     */
    private String title;

    /**
     * 描述
     */
    private String description;

    /**
     * 拍摄日期
     */
    private LocalDate photoDate;

    /**
     * 上传人ID
     */
    private Long uploaderId;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 宽度
     */
    private Integer width;

    /**
     * 高度
     */
    private Integer height;

    /**
     * 关联里程碑ID
     */
    private Long milestoneId;

    /**
     * 排序
     */
    private Integer sortOrder;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
