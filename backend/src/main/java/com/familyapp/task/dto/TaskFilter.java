package com.familyapp.task.dto;

import com.familyapp.task.TaskSource;
import com.familyapp.task.TaskStatus;
import java.time.LocalDate;

/**
 * 任务列表查询条件。
 * <p>status/categoryId/source/keyword/dueBefore 为既有筛选维度（V6 起）。
 * assigneeId 与 overdue 为首页统计卡片跳转与「我的任务」筛选新增维度。</p>
 * <ul>
 *   <li>assigneeId：传值时仅返回该用户作为执行人的任务；为 null 时不过滤（家庭全量）。</li>
 *   <li>overdue：true 时仅返回 dueDate 早于 today 且状态不在 COMPLETED/CANCELLED 的任务。</li>
 * </ul>
 */
public record TaskFilter(
    TaskStatus status,
    Long categoryId,
    TaskSource source,
    String keyword,
    LocalDate dueBefore,
    Long assigneeId,
    Boolean overdue,
    LocalDate today
) {
    /** 旧调用方兼容：仅传 5 维时补默认值。 */
    public TaskFilter(TaskStatus status, Long categoryId, TaskSource source, String keyword, LocalDate dueBefore) {
        this(status, categoryId, source, keyword, dueBefore, null, null, null);
    }
}
