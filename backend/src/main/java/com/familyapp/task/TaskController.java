package com.familyapp.task;

import com.familyapp.common.Result;
import com.familyapp.task.dto.CreateTaskRequest;
import com.familyapp.task.dto.TaskFilter;
import com.familyapp.task.dto.TaskResponse;
import com.familyapp.task.dto.TaskStats;
import com.familyapp.task.dto.UpdateTaskRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService service;

    public TaskController(TaskService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<TaskResponse> create(@Valid @RequestBody CreateTaskRequest req) {
        return Result.ok(service.create(req));
    }

    @GetMapping
    public Result<List<TaskResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) LocalDate dueBefore,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) Boolean overdue
    ) {
        TaskStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            statusEnum = TaskStatus.valueOf(status.toUpperCase());
        }
        TaskSource sourceEnum = null;
        if (source != null && !source.isBlank()) {
            sourceEnum = TaskSource.valueOf(source.toUpperCase());
        }
        TaskFilter filter = new TaskFilter(statusEnum, categoryId, sourceEnum, keyword, dueBefore,
                assigneeId, overdue, LocalDate.now());
        return Result.ok(service.list(filter));
    }

    /** 分页列表与旧数组接口并存；任务页与首页只读取需要的数据。 */
    @GetMapping("/page")
    public Result<java.util.Map<String, Object>> page(
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = service.page(new TaskFilter(status, null, null, null, null,
                assigneeId, overdue, LocalDate.now()), page, size);
        return Result.ok(java.util.Map.of("items", result.getContent(), "total", result.getTotalElements(),
                "page", page, "size", size));
    }

    /** 首页统计：4 个数字（家庭待办/本人待办/家庭逾期/本人逾期）。 */
    @GetMapping("/stats")
    public Result<TaskStats> stats() {
        return Result.ok(service.stats());
    }

    @GetMapping("/{id}")
    public Result<TaskResponse> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    @PutMapping("/{id}")
    public Result<TaskResponse> update(@PathVariable Long id,
                                       @Valid @RequestBody UpdateTaskRequest req) {
        return Result.ok(service.update(id, req));
    }

    @PostMapping("/{id}/status")
    public Result<TaskResponse> changeStatus(@PathVariable Long id,
                                             @RequestBody UpdateTaskRequest req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok(true);
    }
}