package com.familyapp.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * C 组 18 端点请求/响应 DTO 集合。
 * JSON 字段名对齐规格 §3.4 的 snake_case（@JsonProperty 显式声明，不依赖全局命名策略）。
 */
public final class AgentApiDtos {

    private AgentApiDtos() {
    }

    // ==================== 请求 ====================

    /** C-3 创建任务请求。source 强制为 agent，不接受外部指定。 */
    public record TaskCreateReq(
            String title,
            String description,
            @JsonProperty("category_id") Long categoryId,
            String priority,
            @JsonProperty("expires_at") Instant expiresAt,
            @JsonProperty("assignee_id") Long assigneeId) {
    }

    /** C-5 更新任务请求（全字段可选，仅非空生效）。 */
    public record TaskUpdateReq(
            String title,
            String description,
            String priority,
            String status,
            @JsonProperty("category_id") Long categoryId,
            @JsonProperty("due_date") LocalDate dueDate) {
    }

    /** C-6 更新子待办请求。 */
    public record SubtaskUpdateReq(
            String status,
            String title,
            String description,
            @JsonProperty("sort_order") Integer sortOrder) {
    }

    public record BatchSubtaskItem(
            String title,
            String description,
            @JsonProperty("sort_order") Integer sortOrder) {
    }

    /** C-4 批量新增子待办请求。 */
    public record BatchSubtaskReq(
            @JsonProperty("task_id") Long taskId,
            List<BatchSubtaskItem> subtasks) {
    }

    /** C-8 新增便签请求。target_type ∈ {task, subtask}。 */
    public record NoteCreateReq(
            @JsonProperty("target_type") String targetType,
            @JsonProperty("target_id") Long targetId,
            String content) {
    }

    /** C-18 模板新版本请求。 */
    public record NewVersionReq(
            JsonNode content,
            @JsonProperty("change_summary") String changeSummary,
            @JsonProperty("is_important") Boolean isImportant) {
    }

    /** C-17 任务完成事件请求。 */
    public record OnCompletedReq(
            @JsonProperty("source_task_id") Long sourceTaskId) {
    }

    public record SummaryFailedCategory(
            @JsonProperty("category_id") Long categoryId,
            String reason) {
    }

    /** C-14 总结执行日志请求。status ∈ {success, failed}。 */
    public record SummaryLogReq(
            String status,
            @JsonProperty("categories_updated") List<Long> categoriesUpdated,
            @JsonProperty("failed_categories") List<SummaryFailedCategory> failedCategories,
            @JsonProperty("trigger_source") String triggerSource) {
    }

    /** C-19 抢锁请求（trigger_source: auto|manual）。 */
    public record SummaryClaimReq(
            @JsonProperty("trigger_source") String triggerSource) {
    }

    /** C-19 抢锁响应。 */
    public record SummaryClaimResp(
            boolean acquired,
            @JsonProperty("log_id") Long logId) {
    }

    /** C-20 收尾请求（running → success/failed）。 */
    public record SummaryLogFinishReq(
            String status,
            @JsonProperty("categories_updated") List<Long> categoriesUpdated,
            @JsonProperty("failed_categories") List<SummaryFailedCategory> failedCategories) {
    }

    /** C-21 change_summary 追加请求。 */
    public record ChangeSummaryAppendReq(String append) {
    }

    /** C-22 版本清理响应。 */
    public record CleanupResp(int deleted) {
    }

    /** C-24 模板版本历史条目（diff 基线 + 学习轨迹）。 */
    public record TemplateVersionItem(
            Integer version,
            JsonNode content,
            @JsonProperty("change_summary") String changeSummary,
            @JsonProperty("is_important") Boolean isImportant,
            @JsonProperty("created_at") Instant createdAt) {
    }

    /** C-24 模板版本历史响应（最新版本在前）。 */
    public record TemplateVersionsResp(
            @JsonProperty("template_id") Long templateId,
            List<TemplateVersionItem> versions) {
    }

    // ==================== 响应 ====================

    public record SubtaskResp(
            Long id,
            @JsonProperty("parent_task_id") Long parentTaskId,
            String title,
            String description,
            @JsonProperty("sort_order") Integer sortOrder,
            String status,
            @JsonProperty("is_ai_generated") Boolean isAiGenerated) {
    }

    public record NoteResp(
            Long id,
            @JsonProperty("target_type") String targetType,
            @JsonProperty("target_id") Long targetId,
            String content,
            @JsonProperty("created_at") Instant createdAt) {
    }

    /** 任务视图；withChildren=true 时 subtasks/notes 为嵌套列表，否则为 null/空。 */
    public record TaskResp(
            Long id,
            String title,
            String description,
            String status,
            String priority,
            @JsonProperty("category_id") Long categoryId,
            @JsonProperty("assignee_id") Long assigneeId,
            @JsonProperty("due_date") LocalDate dueDate,
            @JsonProperty("expires_at") Instant expiresAt,
            List<SubtaskResp> subtasks,
            List<NoteResp> notes) {
    }

    public record CategoryResp(
            Long id,
            String name,
            String icon,
            String color,
            @JsonProperty("sort_order") Integer sortOrder) {
    }

    public record TemplateResp(
            Long id,
            String name,
            String type,
            Integer version,
            @JsonProperty("is_active") Boolean isActive,
            JsonNode conditions,
            JsonNode content,
            @JsonProperty("category_id") Long categoryId) {
    }

    public record TaskWithSubtasks(
            Long id,
            String title,
            String description,
            String priority,
            String status,
            List<SubtaskResp> subtasks) {
    }

    /** C-12 few-shot：最近完成的带子待办任务样本。 */
    public record FewShotResp(
            @JsonProperty("tasks_with_subtasks") List<TaskWithSubtasks> tasksWithSubtasks) {
    }

    /** C-13 工作量统计。 */
    public record WorkloadResp(
            Integer pending,
            Integer completed,
            @JsonProperty("high_priority") Integer highPriority) {
    }

    /** C-16 原子消化返回的调度行。 */
    public record ScheduleResp(
            Long id,
            @JsonProperty("family_id") Long familyId,
            @JsonProperty("trigger_date") LocalDate triggerDate,
            @JsonProperty("target_template") JsonNode targetTemplate,
            @JsonProperty("is_executed") Boolean isExecuted) {
    }

    /** C-14 返回日志 id。 */
    public record SummaryLogResp(
            Long id,
            @JsonProperty("created_at") Instant createdAt) {
    }

    /** C-17 返回本轮触发数。 */
    public record TriggeredResp(Integer triggered) {
    }

    /** C-18 返回新版本号。 */
    public record VersionResp(@JsonProperty("new_version") int newVersion) {
    }

    /** C-4 批量新增子待办响应。 */
    public record BatchSubtaskResp(
            @JsonProperty("created_count") int createdCount,
            List<Long> ids) {
    }

    /** needs_ai 双键兼容：优先 needs_ai_decomposition，回退 needs_ai（V6 种子用后者）。 */
    public static boolean needsAi(JsonNode node) {
        return node.hasNonNull("needs_ai_decomposition")
                ? node.path("needs_ai_decomposition").asBoolean(false)
                : node.path("needs_ai").asBoolean(false);
    }
}
