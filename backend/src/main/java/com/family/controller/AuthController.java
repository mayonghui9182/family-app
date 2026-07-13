package com.family.controller;

import com.family.common.result.Result;
import com.family.dto.CreateFamilyDTO;
import com.family.dto.JoinFamilyDTO;
import com.family.service.AuthService;
import com.family.vo.LoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器
 */
@Tag(name = "认证管理", description = "家庭组创建、加入等认证相关接口")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 创建家庭组
     *
     * @param dto 创建家庭组信息
     * @return 登录信息
     */
    @Operation(summary = "创建家庭组", description = "创建新的家庭组并成为管理员")
    @PostMapping("/create-family")
    public Result<LoginVO> createFamily(@Valid @RequestBody CreateFamilyDTO dto) {
        LoginVO loginVO = authService.createFamily(dto);
        return Result.success(loginVO);
    }

    /**
     * 加入家庭组
     *
     * @param dto 加入家庭组信息
     * @return 登录信息
     */
    @Operation(summary = "加入家庭组", description = "通过邀请码加入家庭组")
    @PostMapping("/join-family")
    public Result<LoginVO> joinFamily(@Valid @RequestBody JoinFamilyDTO dto) {
        LoginVO loginVO = authService.joinFamily(dto);
        return Result.success(loginVO);
    }

}
