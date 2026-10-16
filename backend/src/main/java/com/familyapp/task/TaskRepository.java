package com.familyapp.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {

    @Query(value = "SELECT * FROM tasks WHERE id = :id", nativeQuery = true)
    Optional<Task> findByIdIncludeDeleted(@Param("id") Long id);

    List<Task> findByStatusAndExpiresAtBefore(TaskStatus status, Instant expiresAt);

    List<Task> findByFamilyIdAndDeletedAtIsNull(Long familyId);

    /** family_id 作用域查找（内部 Agent 端点用，防跨家庭访问）。 */
    Optional<Task> findByIdAndFamilyId(Long id, Long familyId);

    /** Change 12：早报——某日到期的未完成任务数（deletedAt 为空、状态不在 doneStatuses）。 */
    long countByFamilyIdAndDeletedAtIsNullAndStatusNotInAndDueDate(
            Long familyId, Collection<TaskStatus> doneStatuses, LocalDate dueDate);

    /** Change 12：早报——已逾期（dueDate 早于指定日）的未完成任务数。 */
    long countByFamilyIdAndDeletedAtIsNullAndStatusNotInAndDueDateBefore(
            Long familyId, Collection<TaskStatus> doneStatuses, LocalDate dueDateBefore);

    /** 首页统计——家庭未完成（PENDING）任务数。 */
    long countByFamilyIdAndDeletedAtIsNullAndStatus(
            Long familyId, TaskStatus status);

    /** 首页统计——本人未完成（PENDING）任务数（assignee_id 命中当前用户）。 */
    long countByFamilyIdAndAssigneeIdAndDeletedAtIsNullAndStatus(
            Long familyId, Long assigneeId, TaskStatus status);

    /** 删除人员档案前确认：当前家庭内仍在处理的任务不能失去指派对象。 */
    long countByFamilyIdAndAssigneeProfileIdAndDeletedAtIsNullAndStatusIn(
            Long familyId, Long assigneeProfileId, Collection<TaskStatus> statuses);

    /** 首页统计——本人逾期未完成任务数（assignee_id 命中当前用户、状态不在 COMPLETED/CANCELLED、dueDate 早于 today）。 */
    @Query("SELECT COUNT(t) FROM Task t " +
           "WHERE t.familyId = :familyId " +
           "AND t.assigneeId = :assigneeId " +
           "AND t.deletedAt IS NULL " +
           "AND t.status NOT IN :doneStatuses " +
           "AND t.dueDate IS NOT NULL AND t.dueDate < :today")
    long countMineOverdue(@Param("familyId") Long familyId,
                          @Param("assigneeId") Long assigneeId,
                          @Param("doneStatuses") Collection<TaskStatus> doneStatuses,
                          @Param("today") LocalDate today);

    @Query("SELECT t FROM Task t " +
           "WHERE t.familyId = :familyId " +
           "AND t.deletedAt IS NULL " +
           "AND (:status IS NULL OR t.status = :status) " +
           "AND (:categoryId IS NULL OR t.categoryId = :categoryId) " +
           "AND (:source IS NULL OR t.source = :source) " +
           "AND (:keyword IS NULL " +
               "OR t.title ILIKE CAST(CONCAT('%', :keyword, '%') AS string) " +
               "OR t.description ILIKE CAST(CONCAT('%', :keyword, '%') AS string)) " +
           "AND (:dueBefore IS NULL OR t.dueDate <= :dueBefore) " +
           "ORDER BY t.createdAt DESC")
    List<Task> findWithFilters(
        @Param("familyId") Long familyId,
        @Param("status") TaskStatus status,
        @Param("categoryId") Long categoryId,
        @Param("source") TaskSource source,
        @Param("keyword") String keyword,
        @Param("dueBefore") LocalDate dueBefore
    );
}
