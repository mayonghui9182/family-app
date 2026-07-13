package com.family.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 疫苗实体类
 */
@Data
@TableName("vaccine")
public class Vaccine {

    /**
     * 疫苗ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 疫苗名称
     */
    private String name;

    /**
     * 疫苗简称
     */
    private String shortName;

    /**
     * 疫苗类型（free-免费，paid-自费）
     */
    private String type;

    /**
     * 预防疾病
     */
    private String preventDisease;

    /**
     * 接种剂次
     */
    private Integer doses;

    /**
     * 建议接种月龄
     */
    private String recommendAge;

    /**
     * 接种部位
     */
    private String injectionSite;

    /**
     * 疫苗描述
     */
    private String description;

    /**
     * 注意事项
     */
    private String precautions;

    /**
     * 不良反应
     */
    private String adverseReactions;

    /**
     * 排序
     */
    private Integer sortOrder;

    /**
     * 状态（0-禁用，1-启用）
     */
    private Integer status;

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
