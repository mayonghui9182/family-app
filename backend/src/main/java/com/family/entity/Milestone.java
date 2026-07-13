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
 * 成长里程碑实体类
 */
@Data
@TableName("milestone")
public class Milestone {

    /**
     * 里程碑ID
     */
    @TableId(type = IdType.AUTO)
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
