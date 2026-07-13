package com.family.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 宝宝相册实体类
 */
@Data
@TableName("baby_album")
public class BabyAlbum {

    /**
     * 相册ID
     */
    @TableId(type = IdType.AUTO)
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

    /**
     * 逻辑删除（0-未删除，1-已删除）
     */
    @TableLogic
    @TableField(select = false)
    private Integer deleted;

}
