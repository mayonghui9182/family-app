package com.familyapp.task;

import com.familyapp.task.dto.TaskResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Change 13: Task 实体新增 dueTime/sourceEventId 字段验证
 */
class TaskCalendarFieldsTest {

    @Test
    void task_应有dueTime和sourceEventId字段() {
        Task task = new Task();
        task.setDueTime("10:00");
        task.setSourceEventId(42L);
        assertThat(task.getDueTime()).isEqualTo("10:00");
        assertThat(task.getSourceEventId()).isEqualTo(42L);
    }

    @Test
    void taskSource_应包含CALENDAR() {
        assertThat(TaskSource.valueOf("CALENDAR")).isEqualTo(TaskSource.CALENDAR);
    }

    @Test
    void taskResponse_应包含dueTime和sourceEventId() {
        Task task = new Task();
        task.setTitle("周会");
        task.setDueTime("10:00");
        task.setSourceEventId(7L);
        task.setFamilyId(1L);
        TaskResponse resp = TaskResponse.fromEntity(task);
        assertThat(resp.dueTime()).isEqualTo("10:00");
        assertThat(resp.sourceEventId()).isEqualTo(7L);
    }
}
