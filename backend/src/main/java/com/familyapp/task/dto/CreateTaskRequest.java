package com.familyapp.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public class CreateTaskRequest {

    @NotBlank(message = "title is blank")
    @Size(max = 200, message = "title exceeds 200 chars")
    private String title;

    private String description;

    private LocalDate dueDate;

    private String status;

    private String priority;

    private String source;

    private Long categoryId;

    private Long assigneeId;

    /**
     * Change 8 过渡策略（双字段共存 1 大版本）：
     * 新 UI 传 assigneeProfileId（指向 person_profiles.id，支持宝宝/宠物等虚拟成员）；
     * 旧 UI 传 assigneeId（兼容注册用户）。TaskServiceImpl.createTask 按 3 分支处理。
     */
    private Long assigneeProfileId;

    /**
     * 可选：显式指定 family_id（InternalAgentB 组端点 / 单元测试等非 HTTP 上下文使用）。
     * 空时 fallback 到 UserContext.current().familyId()。
     */
    private Long familyId;

    /**
     * 可选：显式指定 created_by（InternalAgentB 端点 / 单元测试等非 HTTP 上下文使用）。
     * 空时 fallback 到 UserContext.current().userId()。
     */
    private Long createdBy;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public Long getAssigneeId() { return assigneeId; }
    public void setAssigneeId(Long assigneeId) { this.assigneeId = assigneeId; }
    public Long getAssigneeProfileId() { return assigneeProfileId; }
    public void setAssigneeProfileId(Long assigneeProfileId) { this.assigneeProfileId = assigneeProfileId; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long familyId) { this.familyId = familyId; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
}