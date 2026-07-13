package com.family.controller;

import com.family.common.result.Result;
import com.family.dto.ReminderDTO;
import com.family.service.ReminderService;
import com.family.vo.ReminderVO;
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
 * 提醒控制器
 */
@Tag(name = "提醒管理", description = "提醒事项相关接口")
@RestController
@RequestMapping("/reminders")
@RequiredArgsConstructor
public class ReminderController {

    private final ReminderService reminderService;

    /**
     * 获取提醒列表
     *
     * @return 提醒列表
     */
    @Operation(summary = "获取提醒列表", description = "获取当前家庭的所有提醒事项")
    @GetMapping
    public Result<List<ReminderVO>> getList() {
        List<ReminderVO> list = reminderService.getList();
        return Result.success(list);
    }

    /**
     * 新增提醒
     *
     * @param dto 提醒信息
     * @return 新增的提醒
     */
    @Operation(summary = "新增提醒", description = "创建新的提醒事项")
    @PostMapping
    public Result<ReminderVO> create(@Valid @RequestBody ReminderDTO dto) {
        ReminderVO reminder = reminderService.create(dto);
        return Result.success(reminder);
    }

    /**
     * 删除提醒
     *
     * @param id 提醒ID
     * @return 操作结果
     */
    @Operation(summary = "删除提醒", description = "根据ID删除提醒事项")
    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @Parameter(description = "提醒ID")
            @PathVariable Long id) {
        reminderService.delete(id);
        return Result.success();
    }

    /**
     * 切换提醒开关
     *
     * @param id 提醒ID
     * @return 切换后的提醒
     */
    @Operation(summary = "切换提醒开关", description = "切换提醒的启用/禁用状态")
    @PutMapping("/{id}/toggle")
    public Result<ReminderVO> toggle(
            @Parameter(description = "提醒ID")
            @PathVariable Long id) {
        ReminderVO reminder = reminderService.toggle(id);
        return Result.success(reminder);
    }

    /**
     * 稍后提醒
     *
     * @param id      提醒ID
     * @param minutes 延后分钟数，默认5分钟
     * @return 更新后的提醒
     */
    @Operation(summary = "稍后提醒", description = "将提醒延后指定分钟数后再次提醒")
    @PutMapping("/{id}/snooze")
    public Result<ReminderVO> snooze(
            @Parameter(description = "提醒ID")
            @PathVariable Long id,
            @Parameter(description = "延后分钟数")
            @RequestParam(defaultValue = "5") int minutes) {
        ReminderVO reminder = reminderService.snooze(id, minutes);
        return Result.success(reminder);
    }
}
