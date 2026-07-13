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
 * 宝宝实体类
 */
@Data
@TableName("baby")
public class Baby {

    /**
     * 宝宝ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 宝宝姓名
     */
    private String name;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 性别（boy-男，girl-女）
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
    private java.math.BigDecimal birthHeight;

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
