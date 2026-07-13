package com.family.controller;

import com.family.common.result.Result;
import com.family.dto.TodoDTO;
import com.family.service.TodoService;
import com.family.vo.PageVO;
import com.family.vo.TodoVO;
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
 * 待办控制器
 */
@Tag(name = "待办管理", description = "待办事项相关接口")
@RestController
@RequestMapping("/todos")
@RequiredArgsConstructor
public class TodoController {

    private final TodoService todoService;

    /**
     * 获取今日待办
     *
     * @return 今日待办列表
     */
    @Operation(summary = "获取今日待办", description = "获取当前家庭的今日待办事项列表")
    @GetMapping("/today")
    public Result<List<TodoVO>> getTodayList() {
        List<TodoVO> list = todoService.getTodayList();
        return Result.success(list);
    }

    /**
     * 分页获取待办列表
     *
     * @param page     页码
     * @param size     每页大小
     * @param status   状态（completed/pending）
     * @param category 分类
     * @return 分页结果
     */
    @Operation(summary = "分页获取待办列表", description = "支持按状态和分类筛选待办事项")
    @GetMapping
    public Result<PageVO<TodoVO>> getList(
            @Parameter(description = "页码，默认1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页大小，默认10")
            @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "状态（completed-已完成，pending-未完成）")
            @RequestParam(required = false) String status,
            @Parameter(description = "分类")
            @RequestParam(required = false) String category) {
        PageVO<TodoVO> result = todoService.getList(page, size, status, category);
        return Result.success(result);
    }

    /**
     * 创建待办
     *
     * @param dto 待办信息
     * @return 创建的待办
     */
    @Operation(summary = "创建待办", description = "创建新的待办事项，可选择创建关联提醒")
    @PostMapping
    public Result<TodoVO> create(@Valid @RequestBody TodoDTO dto) {
        TodoVO todo = todoService.create(dto);
        return Result.success(todo);
    }

    /**
     * 切换完成状态
     *
     * @param id 待办ID
     * @return 切换后的待办
     */
    @Operation(summary = "切换完成状态", description = "切换待办事项的完成状态，完成时自动处理关联提醒")
    @PutMapping("/{id}/toggle")
    public Result<TodoVO> toggleComplete(
            @Parameter(description = "待办ID")
            @PathVariable Long id) {
        TodoVO todo = todoService.toggleComplete(id);
        return Result.success(todo);
    }

    /**
     * 删除待办
     *
     * @param id 待办ID
     * @return 操作结果
     */
    @Operation(summary = "删除待办", description = "根据ID删除待办事项，同时删除关联的提醒")
    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @Parameter(description = "待办ID")
            @PathVariable Long id) {
        todoService.delete(id);
        return Result.success();
    }
}
