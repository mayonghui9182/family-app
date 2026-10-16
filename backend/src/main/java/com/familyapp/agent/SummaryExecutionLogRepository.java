package com.familyapp.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/** 总结执行日志数据访问（C-14 写入 / C-19 抢锁 / C-20 收尾）。 */
public interface SummaryExecutionLogRepository extends JpaRepository<SummaryExecutionLog, Long> {

    /** C-19：指定 [start, end) 窗口内的执行记录（冲突定位 / force 清理）。 */
    List<SummaryExecutionLog> findByFamilyIdAndRunAtGreaterThanEqualAndRunAtLessThan(
            Long familyId, Instant start, Instant end);

    /** C-33：按家庭查执行日志，倒序。 */
    List<SummaryExecutionLog> findByFamilyIdOrderByRunAtDesc(Long familyId);

}
