package com.family.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 旅游亮点实体类
 */
@Data
@TableName("travel_highlight")
public class TravelHighlight {

    /**
     * 亮点ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 攻略ID
     */
    private Long guideId;

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
    private java.math.BigDecimal duration;

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
