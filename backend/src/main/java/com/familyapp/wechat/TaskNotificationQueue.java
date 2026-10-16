package com.familyapp.wechat;

import com.familyapp.task.Task;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskNotificationQueue {
    private final TaskNotificationRepository repository;
    public TaskNotificationQueue(TaskNotificationRepository repository) { this.repository = repository; }

    /** 加入调用方事务；事务回滚时提醒也回滚，绝不提前外呼。 */
    @Transactional
    public void enqueue(Task task) {
        if (task.getAssigneeId() == null && task.getAssigneeProfileId() == null) return;
        TaskNotification n = new TaskNotification();
        n.familyId = task.getFamilyId(); n.taskId = task.getId();
        repository.save(n);
    }
}
