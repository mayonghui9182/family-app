package com.familyapp.task;

import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Expression;
import java.time.Instant;

/**
 * Task 查询规约工具集
 *
 * <p>提供可复用的 JPA Specification 静态方法，用于在 TaskRepository 中组合查询条件。
 * 当前实现：
 * <ul>
 *   <li>notDeleted()：软删除过滤，隐藏 deletedAt IS NOT NULL 的任务</li>
 *   <li>withValidDraftPredicate()：默认 DRAFT 过期过滤，隐藏 expires_at <= NOW 的 DRAFT 任务</li>
 * </ul>
 */
public final class TaskSpecifications {

    private TaskSpecifications() {
        // 工具类，禁止实例化
    }

    /**
     * 软删除过滤规约
     *
     * <p>过滤逻辑：deletedAt IS NULL
     * <br>效果：排除所有已软删除的任务，保持与原 findWithFilters @Query 的 AND t.deletedAt IS NULL 语义一致。
     *
     * @return 过滤软删除任务的 Specification
     */
    public static Specification<Task> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    /**
     * 默认 DRAFT 过期过滤规约
     *
     * <p>过滤逻辑：status != DRAFT OR (status = DRAFT AND expires_at > NOW)
     * <br>效果：默认隐藏已过期的 DRAFT 任务，未过期 DRAFT 和其他状态正常显示。
     * <br>当显式 ?status=DRAFT 时，此规约仍生效，仅返回未过期 DRAFT。
     *
     * @return 过滤过期 DRAFT 的 Specification
     */
    public static Specification<Task> withValidDraftPredicate() {
        return (root, query, cb) -> cb.or(
            cb.notEqual(root.get("status"), TaskStatus.DRAFT),
            cb.and(
                cb.equal(root.get("status"), TaskStatus.DRAFT),
                cb.greaterThan(root.get("expiresAt"), (Expression<Instant>) (Expression) cb.currentTimestamp())
            )
        );
    }
}
