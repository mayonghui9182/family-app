package com.familyapp.task.dto;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public class UpdateTaskRequest {

    @Size(max = 200, message = "title exceeds 200 chars")
    private String title;

    private String description;

    private LocalDate dueDate;

    private String status;

    private String priority;

    private String source;

    private Long categoryId;

    private Long assigneeId;
    private Long assigneeProfileId;
    private boolean assigneeProfileProvided;

    public Long getAssigneeProfileId() { return assigneeProfileId; }
    public void setAssigneeProfileId(Long value) {
        assigneeProfileProvided = true;
        assigneeProfileId = value;
    }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isAssigneeProfileProvided() { return assigneeProfileProvided; }

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
}