package com.family.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 家庭物品实体类
 */
@Data
@TableName("household_item")
public class HouseholdItem {

    /**
     * 物品ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 家庭ID
     */
    private Long familyId;

    /**
     * 物品名称
     */
    private String name;

    /**
     * 分类（diaper-尿不湿，milk-奶粉，food-食品，daily-日用品，medicine-药品，other-其他）
     */
    private String category;

    /**
     * 单位（个、包、袋、瓶、盒）
     */
    private String unit;

    /**
     * 当前库存数量
     */
    private BigDecimal totalQuantity;

    /**
     * 预警数量（低于此值提醒）
     */
    private BigDecimal warningQuantity;

    /**
     * 图标/表情
     */
    private String icon;

    /**
     * 备注
     */
    private String remark;

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
