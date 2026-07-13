package com.family.vo;

import lombok.Data;

import java.time.LocalDate;

/**
 * 宝宝疫苗VO
 */
@Data
public class BabyVaccineVO {

    /**
     * 记录ID
     */
    private Long id;

    /**
     * 疫苗ID
     */
    private Long vaccineId;

    /**
     * 疫苗名称
     */
    private String vaccineName;

    /**
     * 疫苗简称
     */
    private String shortName;

    /**
     * 疫苗类型（free-免费，paid-自费）
     */
    private String type;

    /**
     * 剂次
     */
    private Integer doseNumber;

    /**
     * 总剂次
     */
    private Integer totalDoses;

    /**
     * 预防疾病
     */
    private String preventDisease;

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
     * 是否开启提醒
     */
    private Integer remindEnabled;

    /**
     * 提前几天提醒
     */
    private Integer remindDaysBefore;

    /**
     * 是否已提醒
     */
    private Integer reminded;

    /**
     * 疫苗描述
     */
    private String description;

    /**
     * 注意事项
     */
    private String precautions;

    /**
     * 是否逾期
     */
    private Boolean isOverdue;

    /**
     * 还有几天/已逾期几天（正数表示还有几天，负数表示已逾期几天）
     */
    private Integer daysLeft;

}
