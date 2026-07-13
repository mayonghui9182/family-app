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
 * 宝宝疫苗接种记录实体类
 */
@Data
@TableName("baby_vaccine")
public class BabyVaccine {

    /**
     * 记录ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 宝宝ID
     */
    private Long babyId;

    /**
     * 疫苗ID
     */
    private Long vaccineId;

    /**
     * 剂次
     */
    private Integer doseNumber;

    /**
     * 计划接种日期
     */
    private LocalDate plannedDate;

    /**
     * 实际接种日期
     */
    private LocalDate actualDate;

    /**
     * 接种状态（pending-待接种，completed-已接种，skipped-已跳过，delayed-已推迟）
     */
    private String status;

    /**
     * 接种部位
     */
    private String injectionSite;

    /**
     * 接种医院
     */
    private String hospital;

    /**
     * 疫苗批号
     */
    private String batchNumber;

    /**
     * 不良反应
     */
    private String adverseReaction;

    /**
     * 备注
     */
    private String remark;

    /**
     * 是否开启提醒（0-关闭，1-开启）
     */
    private Integer remindEnabled;

    /**
     * 提前几天提醒
     */
    private Integer remindDaysBefore;

    /**
     * 是否已提醒（0-未提醒，1-已提醒）
     */
    private Integer reminded;

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
