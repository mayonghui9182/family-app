package com.family.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 宝宝照片实体类
 */
@Data
@TableName("baby_photo")
public class BabyPhoto {

    /**
     * 照片ID
     */
    @TableId(type = IdType.AUTO)
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

    /**
     * 逻辑删除（0-未删除，1-已删除）
     */
    @TableLogic
    @TableField(select = false)
    private Integer deleted;

}
