package com.family.controller;

import com.family.common.result.Result;
import com.family.entity.HouseholdItem;
import com.family.service.HouseholdItemService;
import com.family.vo.HouseholdItemVO;
import com.family.vo.ItemRecordVO;
import com.family.vo.ItemStatsVO;
import com.family.vo.PageVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 家庭物品库存控制器
 */
@Tag(name = "家庭物品库存", description = "家庭物品库存管理相关接口")
@RestController
@RequestMapping("/items")
@RequiredArgsConstructor
public class HouseholdItemController {

    private final HouseholdItemService householdItemService;

    /**
     * 获取物品列表
     *
     * @param category 分类（可选）
     * @return 物品列表
     */
    @Operation(summary = "获取物品列表", description = "获取当前家庭的物品列表，可按分类筛选，低库存优先排序")
    @GetMapping
    public Result<List<HouseholdItemVO>> getItemList(
            @Parameter(description = "分类（diaper-尿不湿，milk-奶粉，food-食品，daily-日用品，medicine-药品，other-其他）")
            @RequestParam(required = false) String category) {
        List<HouseholdItemVO> list = householdItemService.getItemList(category);
        return Result.success(list);
    }

    /**
     * 获取物品详情
     *
     * @param id 物品ID
     * @return 物品详情
     */
    @Operation(summary = "获取物品详情", description = "根据ID获取物品详情")
    @GetMapping("/{id}")
    public Result<HouseholdItemVO> getItemDetail(
            @Parameter(description = "物品ID")
            @PathVariable Long id) {
        HouseholdItemVO item = householdItemService.getItemDetail(id);
        return Result.success(item);
    }

    /**
     * 新增物品
     *
     * @param item 物品信息
     * @return 创建的物品
     */
    @Operation(summary = "新增物品", description = "创建新的家庭物品")
    @PostMapping
    public Result<HouseholdItemVO> createItem(@RequestBody HouseholdItem item) {
        HouseholdItemVO created = householdItemService.createItem(item);
        return Result.success(created);
    }

    /**
     * 修改物品
     *
     * @param id   物品ID
     * @param item 物品信息
     * @return 修改后的物品
     */
    @Operation(summary = "修改物品", description = "根据ID修改物品信息")
    @PutMapping("/{id}")
    public Result<HouseholdItemVO> updateItem(
            @Parameter(description = "物品ID")
            @PathVariable Long id,
            @RequestBody HouseholdItem item) {
        HouseholdItemVO updated = householdItemService.updateItem(id, item);
        return Result.success(updated);
    }

    /**
     * 删除物品
     *
     * @param id 物品ID
     * @return 操作结果
     */
    @Operation(summary = "删除物品", description = "根据ID删除物品，同时删除相关的出入库记录")
    @DeleteMapping("/{id}")
    public Result<Void> deleteItem(
            @Parameter(description = "物品ID")
            @PathVariable Long id) {
        householdItemService.deleteItem(id);
        return Result.success();
    }

    /**
     * 入库
     *
     * @param id         物品ID
     * @param quantity   数量
     * @param recordDate 记录日期
     * @param source     来源
     * @param price      单价
     * @param remark     备注
     * @return 入库记录
     */
    @Operation(summary = "入库", description = "物品入库操作（购买等）")
    @PostMapping("/{id}/stock-in")
    public Result<ItemRecordVO> stockIn(
            @Parameter(description = "物品ID")
            @PathVariable Long id,
            @Parameter(description = "入库数量", required = true)
            @RequestParam BigDecimal quantity,
            @Parameter(description = "记录日期")
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate recordDate,
            @Parameter(description = "来源（购买、赠送等）")
            @RequestParam(required = false) String source,
            @Parameter(description = "单价（元）")
            @RequestParam(required = false) BigDecimal price,
            @Parameter(description = "备注")
            @RequestParam(required = false) String remark) {
        ItemRecordVO record = householdItemService.stockIn(id, quantity, recordDate, source, price, remark);
        return Result.success(record);
    }

    /**
     * 出库
     *
     * @param id         物品ID
     * @param quantity   数量
     * @param recordDate 记录日期
     * @param source     用途
     * @param remark     备注
     * @return 出库记录
     */
    @Operation(summary = "出库", description = "物品出库操作（使用等）")
    @PostMapping("/{id}/stock-out")
    public Result<ItemRecordVO> stockOut(
            @Parameter(description = "物品ID")
            @PathVariable Long id,
            @Parameter(description = "出库数量", required = true)
            @RequestParam BigDecimal quantity,
            @Parameter(description = "记录日期")
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate recordDate,
            @Parameter(description = "用途（使用、赠送等）")
            @RequestParam(required = false) String source,
            @Parameter(description = "备注")
            @RequestParam(required = false) String remark) {
        ItemRecordVO record = householdItemService.stockOut(id, quantity, recordDate, source, remark);
        return Result.success(record);
    }

    /**
     * 获取出入库记录
     *
     * @param id   物品ID
     * @param page 页码
     * @param size 每页大小
     * @return 分页记录
     */
    @Operation(summary = "获取出入库记录", description = "获取物品的出入库记录（分页）")
    @GetMapping("/{id}/records")
    public Result<PageVO<ItemRecordVO>> getRecordList(
            @Parameter(description = "物品ID")
            @PathVariable Long id,
            @Parameter(description = "页码，默认1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页大小，默认10")
            @RequestParam(defaultValue = "10") int size) {
        PageVO<ItemRecordVO> result = householdItemService.getRecordList(id, page, size);
        return Result.success(result);
    }

    /**
     * 获取统计数据
     *
     * @return 统计数据
     */
    @Operation(summary = "获取统计数据", description = "获取家庭物品库存统计数据")
    @GetMapping("/stats")
    public Result<ItemStatsVO> getStats() {
        ItemStatsVO stats = householdItemService.getStats();
        return Result.success(stats);
    }

    /**
     * 获取低库存物品列表
     *
     * @return 低库存物品列表
     */
    @Operation(summary = "获取低库存物品列表", description = "获取当前家庭低库存的物品列表，用于首页提醒")
    @GetMapping("/low-stock")
    public Result<List<HouseholdItemVO>> getLowStockItems() {
        List<HouseholdItemVO> list = householdItemService.getLowStockItems();
        return Result.success(list);
    }
}
