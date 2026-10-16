package com.familyapp.wechat;

import jakarta.persistence.*;
import java.time.Instant;

/** 与任务在同一事务中保存的指派提醒；发送失败不回滚任务。 */
@Entity
@Table(name = "task_notifications")
public class TaskNotification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(name = "family_id", nullable = false) Long familyId;
    @Column(name = "task_id", nullable = false) Long taskId;
    @Column(nullable = false, length = 16) String status = "PENDING";
    @Column(nullable = false) int attempts = 0;
    @Column(name = "available_at", nullable = false) Instant availableAt = Instant.now();
    @Column(name = "created_at", nullable = false) Instant createdAt = Instant.now();
    public Long getId() { return id; }
    public String getStatus() { return status; }
}
