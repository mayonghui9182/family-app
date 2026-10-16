package com.familyapp.task.dto;

import com.familyapp.task.Task;
import com.familyapp.task.TaskPriority;
import com.familyapp.task.TaskSource;
import com.familyapp.task.TaskStatus;

import java.time.Instant;
import java.time.LocalDate;

public record TaskResponse(
    Long id,
    String title,
    String description,
    LocalDate dueDate,
    TaskStatus status,
    TaskPriority priority,
    TaskSource source,
    Long familyId,
    Long createdBy,
    Long assigneeId,
    /** Change 8 过渡：新列 assignee_profile_id（person_profiles.id）优先；null 时 UI fallback assigneeId */
    Long assigneeProfileId,
    Long categoryId,
    Instant expiresAt,
    Instant createdAt,
    Instant updatedAt,
    String dueTime,
    Long sourceEventId
) {
    public static TaskResponse fromEntity(Task t) {
        return new TaskResponse(
            t.getId(), t.getTitle(), t.getDescription(),
            t.getDueDate(), t.getStatus(), t.getPriority(), t.getSource(),
            t.getFamilyId(), t.getCreatedBy(), t.getAssigneeId(), t.getAssigneeProfileId(),
            t.getCategoryId(),
            t.getExpiresAt(), t.getCreatedAt(), t.getUpdatedAt(),
            t.getDueTime(), t.getSourceEventId()
        );
    }
}