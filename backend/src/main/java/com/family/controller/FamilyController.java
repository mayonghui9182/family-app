package com.family.controller;

import com.family.common.result.Result;
import com.family.entity.Family;
import com.family.service.FamilyService;
import com.family.vo.FamilyInviteVO;
import com.family.vo.MemberVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 家庭控制器
 */
@Tag(name = "家庭管理", description = "家庭成员、邀请码相关接口")
@RestController
@RequestMapping("/family")
@RequiredArgsConstructor
public class FamilyController {

    private final FamilyService familyService;

    /**
     * 获取家庭信息
     *
     * @return 家庭信息
     */
    @Operation(summary = "获取家庭信息", description = "获取当前家庭的基本信息")
    @GetMapping("/info")
    public Result<Family> getFamilyInfo() {
        Family family = familyService.getFamilyInfo();
        return Result.success(family);
    }

    /**
     * 获取成员列表
     *
     * @return 成员列表
     */
    @Operation(summary = "获取成员列表", description = "获取当前家庭所有成员列表")
    @GetMapping("/members")
    public Result<List<MemberVO>> getMembers() {
        List<MemberVO> members = familyService.getMembers();
        return Result.success(members);
    }

    /**
     * 更新成员角色
     *
     * @param id   用户ID
     * @param role 角色
     * @return 更新后的成员信息
     */
    @Operation(summary = "更新成员角色", description = "更新指定成员的角色（仅管理员可操作）")
    @PutMapping("/members/{id}/role")
    public Result<MemberVO> updateMemberRole(
            @Parameter(description = "用户ID")
            @PathVariable Long id,
            @Parameter(description = "角色")
            @RequestParam String role) {
        MemberVO member = familyService.updateMemberRole(id, role);
        return Result.success(member);
    }

    /**
     * 移除成员
     *
     * @param id 用户ID
     * @return 操作结果
     */
    @Operation(summary = "移除成员", description = "移除指定成员（仅管理员可操作，不能移除自己）")
    @DeleteMapping("/members/{id}")
    public Result<Void> removeMember(
            @Parameter(description = "用户ID")
            @PathVariable Long id) {
        familyService.removeMember(id);
        return Result.success();
    }

    /**
     * 生成邀请码
     *
     * @param maxCount    最大使用次数
     * @param expireHours 过期时间（小时）
     * @return 邀请码信息
     */
    @Operation(summary = "生成邀请码", description = "生成新的家庭邀请码（仅管理员可操作）")
    @PostMapping("/invite/generate")
    public Result<FamilyInviteVO> generateInviteCode(
            @Parameter(description = "最大使用次数")
            @RequestParam(required = false) Integer maxCount,
            @Parameter(description = "过期时间（小时）")
            @RequestParam(required = false) Integer expireHours) {
        FamilyInviteVO invite = familyService.generateInviteCode(maxCount, expireHours);
        return Result.success(invite);
    }

    /**
     * 获取邀请码列表
     *
     * @return 邀请码列表
     */
    @Operation(summary = "获取邀请码列表", description = "获取当前家庭所有邀请码列表")
    @GetMapping("/invite/list")
    public Result<List<FamilyInviteVO>> getInviteList() {
        List<FamilyInviteVO> invites = familyService.getInviteList();
        return Result.success(invites);
    }

    /**
     * 禁用邀请码
     *
     * @param id 邀请ID
     * @return 操作结果
     */
    @Operation(summary = "禁用邀请码", description = "禁用指定邀请码（仅管理员可操作）")
    @PutMapping("/invite/{id}/disable")
    public Result<Void> disableInvite(
            @Parameter(description = "邀请ID")
            @PathVariable Long id) {
        familyService.disableInvite(id);
        return Result.success();
    }

}
