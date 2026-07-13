package com.family.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建家庭组DTO
 */
@Data
public class CreateFamilyDTO {

    /**
     * 家庭名称
     */
    @NotBlank(message = "家庭名称不能为空")
    private String familyName;

    /**
     * 用户名
     */
    @NotBlank(message = "用户名不能为空")
    private String userName;

}
