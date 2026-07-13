package com.family.controller;

import com.family.common.result.Result;
import com.family.entity.Vaccine;
import com.family.service.VaccineService;
import com.family.vo.BabyVaccineVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 疫苗控制器
 */
@Tag(name = "疫苗管理", description = "疫苗接种计划与提醒相关接口")
@RestController
@RequiredArgsConstructor
public class VaccineController {

    private final VaccineService vaccineService;

    /**
     * 获取疫苗列表
     *
     * @param type 疫苗类型（free-免费，paid-自费，all-全部）
     * @return 疫苗列表
     */
    @Operation(summary = "获取疫苗列表", description = "获取疫苗库列表，可按类型筛选")
    @GetMapping("/vaccine/list")
    public Result<List<Vaccine>> getVaccineList(
            @Parameter(description = "疫苗类型（free/paid/all）")
            @RequestParam(required = false, defaultValue = "all") String type) {
        List<Vaccine> vaccines = vaccineService.getVaccineList(type);
        return Result.success(vaccines);
    }

    /**
     * 获取宝宝疫苗计划
     *
     * @param babyId 宝宝ID
     * @param status 状态（pending-待接种，completed-已接种，skipped-已跳过，all-全部）
     * @return 宝宝疫苗列表
     */
    @Operation(summary = "获取宝宝疫苗计划", description = "获取宝宝的疫苗接种计划列表，可按状态筛选")
    @GetMapping("/baby/{babyId}/vaccines")
    public Result<List<BabyVaccineVO>> getBabyVaccines(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Parameter(description = "状态（pending/completed/skipped/all）")
            @RequestParam(required = false, defaultValue = "all") String status) {
        List<BabyVaccineVO> vaccines = vaccineService.getBabyVaccines(babyId, status);
        return Result.success(vaccines);
    }

    /**
     * 获取疫苗统计
     *
     * @param babyId 宝宝ID
     * @return 统计信息
     */
    @Operation(summary = "获取疫苗统计", description = "获取宝宝疫苗接种统计（已接种数、总数、待接种数）")
    @GetMapping("/baby/{babyId}/vaccines/stats")
    public Result<Map<String, Integer>> getVaccineStats(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId) {
        Map<String, Integer> stats = vaccineService.getVaccineStats(babyId);
        return Result.success(stats);
    }

    /**
     * 标记已接种
     *
     * @param babyId          宝宝ID
     * @param id              记录ID
     * @param hospital        接种医院
     * @param batchNumber     疫苗批号
     * @param adverseReaction 不良反应
     * @param remark          备注
     * @return 更新后的疫苗记录
     */
    @Operation(summary = "标记已接种", description = "标记疫苗为已接种状态")
    @PutMapping("/baby/{babyId}/vaccines/{id}/vaccinated")
    public Result<BabyVaccineVO> markVaccinated(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Parameter(description = "记录ID")
            @PathVariable Long id,
            @Parameter(description = "接种医院")
            @RequestParam(required = false) String hospital,
            @Parameter(description = "疫苗批号")
            @RequestParam(required = false) String batchNumber,
            @Parameter(description = "不良反应")
            @RequestParam(required = false) String adverseReaction,
            @Parameter(description = "备注")
            @RequestParam(required = false) String remark) {
        BabyVaccineVO vaccine = vaccineService.markVaccinated(babyId, id, hospital, batchNumber, adverseReaction, remark);
        return Result.success(vaccine);
    }

    /**
     * 修改计划接种日期
     *
     * @param babyId      宝宝ID
     * @param id          记录ID
     * @param plannedDate 计划接种日期
     * @return 更新后的疫苗记录
     */
    @Operation(summary = "修改计划日期", description = "修改疫苗的计划接种日期")
    @PutMapping("/baby/{babyId}/vaccines/{id}/planned-date")
    public Result<BabyVaccineVO> updatePlannedDate(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Parameter(description = "记录ID")
            @PathVariable Long id,
            @Parameter(description = "计划接种日期")
            @RequestParam LocalDate plannedDate) {
        BabyVaccineVO vaccine = vaccineService.updatePlannedDate(babyId, id, plannedDate);
        return Result.success(vaccine);
    }

    /**
     * 跳过疫苗
     *
     * @param babyId 宝宝ID
     * @param id     记录ID
     * @param reason 跳过原因
     * @return 更新后的疫苗记录
     */
    @Operation(summary = "跳过疫苗", description = "将疫苗标记为已跳过状态")
    @PutMapping("/baby/{babyId}/vaccines/{id}/skip")
    public Result<BabyVaccineVO> skipVaccine(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Parameter(description = "记录ID")
            @PathVariable Long id,
            @Parameter(description = "跳过原因")
            @RequestParam(required = false) String reason) {
        BabyVaccineVO vaccine = vaccineService.skipVaccine(babyId, id, reason);
        return Result.success(vaccine);
    }

    /**
     * 开关提醒
     *
     * @param babyId  宝宝ID
     * @param id      记录ID
     * @param enabled 是否开启（0-关闭，1-开启）
     * @return 更新后的疫苗记录
     */
    @Operation(summary = "开关提醒", description = "开启或关闭疫苗接种提醒")
    @PutMapping("/baby/{babyId}/vaccines/{id}/remind")
    public Result<BabyVaccineVO> toggleRemind(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Parameter(description = "记录ID")
            @PathVariable Long id,
            @Parameter(description = "是否开启提醒（0-关闭，1-开启）")
            @RequestParam Integer enabled) {
        BabyVaccineVO vaccine = vaccineService.toggleRemind(babyId, id, enabled);
        return Result.success(vaccine);
    }

    /**
     * 生成标准疫苗计划
     *
     * @param babyId 宝宝ID
     * @return 生成的疫苗计划列表
     */
    @Operation(summary = "生成标准疫苗计划", description = "根据宝宝出生日期和疫苗库生成标准疫苗接种计划")
    @PostMapping("/baby/{babyId}/vaccines/generate")
    public Result<List<BabyVaccineVO>> generatePlan(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId) {
        List<BabyVaccineVO> vaccines = vaccineService.generatePlan(babyId);
        return Result.success(vaccines);
    }
}
