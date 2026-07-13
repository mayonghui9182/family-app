package com.family.controller;

import com.family.common.result.Result;
import com.family.service.TravelService;
import com.family.vo.HotDestinationVO;
import com.family.vo.PageVO;
import com.family.vo.TravelGuideVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 旅游控制器
 */
@Tag(name = "旅游管理", description = "旅游攻略相关接口")
@RestController
@RequestMapping("/travel")
@RequiredArgsConstructor
public class TravelController {

    private final TravelService travelService;

    /**
     * 获取攻略列表
     *
     * @param keyword 搜索关键词
     * @param current 当前页
     * @param size    每页大小
     * @return 攻略分页列表
     */
    @Operation(summary = "获取攻略列表", description = "支持搜索关键词，分页获取旅游攻略列表")
    @GetMapping("/guides")
    public Result<PageVO<TravelGuideVO>> getGuideList(
            @Parameter(description = "搜索关键词")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "当前页码")
            @RequestParam(defaultValue = "1") Long current,
            @Parameter(description = "每页大小")
            @RequestParam(defaultValue = "10") Long size) {
        PageVO<TravelGuideVO> page = travelService.getGuideList(keyword, current, size);
        return Result.success(page);
    }

    /**
     * 获取攻略详情
     *
     * @param id 攻略ID
     * @return 攻略详情
     */
    @Operation(summary = "获取攻略详情", description = "根据ID获取旅游攻略详情")
    @GetMapping("/guides/{id}")
    public Result<TravelGuideVO> getGuideDetail(
            @Parameter(description = "攻略ID")
            @PathVariable Long id) {
        TravelGuideVO guide = travelService.getGuideDetail(id);
        return Result.success(guide);
    }

    /**
     * 获取热门目的地
     *
     * @return 热门目的地列表
     */
    @Operation(summary = "获取热门目的地", description = "获取热门旅游目的地列表")
    @GetMapping("/hot-destinations")
    public Result<List<HotDestinationVO>> getHotDestinations() {
        List<HotDestinationVO> destinations = travelService.getHotDestinations();
        return Result.success(destinations);
    }
}
