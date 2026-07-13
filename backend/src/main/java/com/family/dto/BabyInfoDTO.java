package com.family.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 宝宝信息DTO
 */
@Data
public class BabyInfoDTO {

    /**
     * 宝宝姓名
     */
    @NotBlank(message = "宝宝姓名不能为空")
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

}
