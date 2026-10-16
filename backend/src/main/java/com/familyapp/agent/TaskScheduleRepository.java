package com.familyapp.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 一次性调度数据访问（C-16 原子消化，design.md D1）。
 *
 * <p>可移植原子语义：先 SELECT 候选 id（最旧一条今日未执行），再执行条件 UPDATE
 * {@code UPDATE task_schedules SET is_executed=true WHERE id=:id AND is_executed=false}，
 * 受影响行数==1 即抢占成功，随后按主键读回该行。与 design.md D1 的单语句写法
 * {@code UPDATE ... WHERE id=(SELECT ...)} 语义等价 —— WHERE 谓词中的
 * is_executed=false 条件保证并发下仅一个事务能置位（其余影响行数为 0）；
 * 先取候选 id 是为了并发抢占成功后能精确读回"自己抢到的那一行"，
 * 单语句形式在并发读回时无法确定归属。</p>
 */
public interface TaskScheduleRepository extends JpaRepository<TaskSchedule, Long> {

    /** 今日该家庭最旧一条未执行的候选调度（无则 empty）。CURRENT_DATE/LIMIT 在 H2 与 PG 双方言可用。 */
    @Query(value = "SELECT id FROM task_schedules "
            + "WHERE family_id = :familyId AND trigger_date = CURRENT_DATE AND is_executed = FALSE "
            + "ORDER BY id LIMIT 1", nativeQuery = true)
    Optional<Long> findNextPendingId(@Param("familyId") Long familyId);

    /** 跨家庭先到先得：今日最旧一条未执行候选（C-16 全局 / Change3 3:00 一次性调度）。 */
    @Query(value = "SELECT id FROM task_schedules "
            + "WHERE trigger_date = CURRENT_DATE AND is_executed = FALSE "
            + "ORDER BY id LIMIT 1", nativeQuery = true)
    Optional<Long> findNextPendingIdGlobal();

    /**
     * 条件 UPDATE 原子抢占：仅在仍为未执行状态时置位。
     * 返回受影响行数：1 = 抢占成功；0 = 已被其它事务消费或不存在。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE task_schedules SET is_executed = TRUE "
            + "WHERE id = :id AND is_executed = FALSE", nativeQuery = true)
    int claimById(@Param("id") Long id);
}
