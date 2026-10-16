package com.familyapp.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 依赖触发规则数据访问（C-17 on_complete 触发链）。 */
public interface TaskTriggerRepository extends JpaRepository<TaskTrigger, Long> {

    /** 源任务的全部生效触发器（is_active=true）。 */
    List<TaskTrigger> findBySourceTaskIdAndIsActiveTrue(Long sourceTaskId);
}
