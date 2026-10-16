package com.familyapp.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

/** C-15 今日命中项：来自模板 content 字段的产出任务定义摘要（JSON 字段 snake_case）。 */
public record DueTodayRoutine(
        Long templateId,
        @JsonProperty("routine_title") String routineTitle,
        String description,
        String priority,
        @JsonProperty("needs_ai") boolean needsAi) {
}
