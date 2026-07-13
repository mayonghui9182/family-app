package com.family.controller;

import com.family.common.result.Result;
import com.family.dto.BabyInfoDTO;
import com.family.dto.GrowthRecordDTO;
import com.family.service.BabyService;
import com.family.vo.BabyInfoVO;
import com.family.vo.BabyVaccineVO;
import com.family.vo.GrowthRecordVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 宝宝控制器
 */
@Tag(name = "宝宝管理", description = "宝宝信息、成长记录、疫苗相关接口")
@RestController
@RequestMapping("/baby/{babyId}")
@RequiredArgsConstructor
public class BabyController {

    private final BabyService babyService;

    /**
     * 获取宝宝信息
     *
     * @param babyId 宝宝ID
     * @return 宝宝信息
     */
    @Operation(summary = "获取宝宝信息", description = "获取指定宝宝的基本信息")
    @GetMapping("/info")
    public Result<BabyInfoVO> getBabyInfo(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId) {
        BabyInfoVO babyInfo = babyService.getBabyInfo(babyId);
        return Result.success(babyInfo);
    }

    /**
     * 更新宝宝信息
     *
     * @param babyId 宝宝ID
     * @param dto    宝宝信息
     * @return 更新后的宝宝信息
     */
    @Operation(summary = "更新宝宝信息", description = "更新宝宝基本信息")
    @PutMapping("/info")
    public Result<BabyInfoVO> updateBabyInfo(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Valid @RequestBody BabyInfoDTO dto) {
        BabyInfoVO babyInfo = babyService.updateBabyInfo(babyId, dto);
        return Result.success(babyInfo);
    }

    /**
     * 获取成长记录列表
     *
     * @param babyId 宝宝ID
     * @param type   记录类型（可选）
     * @return 成长记录列表
     */
    @Operation(summary = "获取成长记录", description = "获取宝宝的成长记录列表，可按类型筛选")
    @GetMapping("/growth")
    public Result<List<GrowthRecordVO>> getGrowthRecords(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Parameter(description = "记录类型")
            @RequestParam(required = false) String type) {
        List<GrowthRecordVO> records = babyService.getGrowthRecords(babyId, type);
        return Result.success(records);
    }

    /**
     * 添加成长记录
     *
     * @param babyId 宝宝ID
     * @param dto    成长记录信息
     * @return 新增的成长记录
     */
    @Operation(summary = "添加成长记录", description = "添加宝宝的成长记录")
    @PostMapping("/growth")
    public Result<GrowthRecordVO> addGrowthRecord(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Valid @RequestBody GrowthRecordDTO dto) {
        GrowthRecordVO record = babyService.addGrowthRecord(babyId, dto);
        return Result.success(record);
    }

    /**
     * 删除成长记录
     *
     * @param babyId 宝宝ID
     * @param id     记录ID
     * @return 操作结果
     */
    @Operation(summary = "删除成长记录", description = "根据ID删除成长记录")
    @DeleteMapping("/growth/{id}")
    public Result<Void> deleteGrowthRecord(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Parameter(description = "成长记录ID")
            @PathVariable Long id) {
        babyService.deleteGrowthRecord(babyId, id);
        return Result.success();
    }

    /**
     * 获取疫苗列表
     *
     * @param babyId 宝宝ID
     * @return 疫苗列表
     */
    @Operation(summary = "获取疫苗列表", description = "获取宝宝的疫苗接种计划和记录")
    @GetMapping("/vaccines")
    public Result<List<BabyVaccineVO>> getVaccines(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId) {
        List<BabyVaccineVO> vaccines = babyService.getVaccines(babyId);
        return Result.success(vaccines);
    }

    /**
     * 标记疫苗完成
     *
     * @param babyId 宝宝ID
     * @param id     疫苗记录ID
     * @return 更新后的疫苗记录
     */
    @Operation(summary = "标记疫苗完成", description = "切换疫苗的接种状态（已接种/待接种）")
    @PutMapping("/vaccines/{id}/toggle")
    public Result<BabyVaccineVO> toggleVaccine(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Parameter(description = "疫苗记录ID")
            @PathVariable Long id) {
        BabyVaccineVO vaccine = babyService.toggleVaccine(babyId, id);
        return Result.success(vaccine);
    }
}
