package com.family.controller;

import com.family.common.result.Result;
import com.family.service.MilestoneService;
import com.family.vo.MilestoneVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 里程碑控制器
 */
@Tag(name = "里程碑管理", description = "宝宝成长里程碑相关接口")
@RestController
@RequestMapping("/baby/{babyId}/milestones")
@RequiredArgsConstructor
public class MilestoneController {

    private final MilestoneService milestoneService;

    /**
     * 获取里程碑列表
     *
     * @param babyId   宝宝ID
     * @param category 分类（可选）
     * @return 里程碑列表
     */
    @Operation(summary = "获取里程碑列表", description = "获取宝宝的成长里程碑列表，可按分类筛选")
    @GetMapping
    public Result<List<MilestoneVO>> getMilestones(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Parameter(description = "分类（motor/fine_motor/language/social）")
            @RequestParam(required = false) String category) {
        List<MilestoneVO> milestones = milestoneService.getMilestones(babyId, category);
        return Result.success(milestones);
    }

    /**
     * 更新里程碑状态
     *
     * @param babyId       宝宝ID
     * @param id           里程碑ID
     * @param status       状态
     * @param achievedDate 达成日期（可选）
     * @param note         备注（可选）
     * @return 更新后的里程碑
     */
    @Operation(summary = "更新里程碑状态", description = "更新里程碑的状态、达成日期和备注")
    @PutMapping("/{id}/status")
    public Result<MilestoneVO> updateStatus(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Parameter(description = "里程碑ID")
            @PathVariable Long id,
            @Parameter(description = "状态（pending/achieved/concerned）")
            @RequestParam String status,
            @Parameter(description = "达成日期")
            @RequestParam(required = false) LocalDate achievedDate,
            @Parameter(description = "备注")
            @RequestParam(required = false) String note) {
        MilestoneVO milestone = milestoneService.updateStatus(babyId, id, status, achievedDate, note);
        return Result.success(milestone);
    }
}
