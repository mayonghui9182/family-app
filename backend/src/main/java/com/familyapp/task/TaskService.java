package com.familyapp.task;

import com.familyapp.task.dto.CreateTaskRequest;
import com.familyapp.task.dto.TaskFilter;
import com.familyapp.task.dto.TaskResponse;
import com.familyapp.task.dto.TaskStats;
import com.familyapp.task.dto.UpdateTaskRequest;

import java.util.List;

public interface TaskService {
    TaskResponse create(CreateTaskRequest req);
    /**
     * 过渡方法：返回底层 Task 实体（InternalAgent 端点/单元测试直接访问 assigneeProfileId 等原始字段）。
     * 内部逻辑与 create(req) 完全一致。
     */
    Task createTask(CreateTaskRequest req);
    List<TaskResponse> list(TaskFilter filter);
    org.springframework.data.domain.Page<TaskResponse> page(TaskFilter filter, int page, int size);
    TaskResponse get(Long id);
    TaskResponse update(Long id, UpdateTaskRequest req);
    void delete(Long id);
    /**
     * 首页统计：返回家庭/本人各 PENDING 与逾期的 4 个数字。
     * family_id 与当前用户 userId 取自 UserContext。
     */
    TaskStats stats();
}
