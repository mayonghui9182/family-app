package com.family.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 宝宝信息VO
 */
@Data
public class BabyInfoVO {

    /**
     * 宝宝ID
     */
    private Long id;

    /**
     * 宝宝姓名
     */
    private String name;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 性别
     */
    private String gender;

    /**
     * 出生日期
     */
    private LocalDate birthDate;

    /**
     * 出生时间
     */
    private String birthTime;

    /**
     * 出生体重（克）
     */
    private Integer birthWeight;

    /**
     * 出生身高（厘米）
     */
    private BigDecimal birthHeight;

    /**
     * 血型
     */
    private String bloodType;

    /**
     * 生肖
     */
    private String zodiac;

    /**
     * 星座
     */
    private String constellation;

    /**
     * 头像
     */
    private String avatar;

    /**
     * 备注
     */
    private String remark;

    /**
     * 月龄
     */
    private Integer monthsAge;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
