package com.familyapp.family;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

/**
 * 家庭数据访问层。
 */
@Repository
public interface FamilyRepository extends JpaRepository<Family, Long> {

    /** C-23（Change 5）：22:00 总结调度批源 —— 全部未软删家庭 ID（机器调用无用户上下文）。 */
    @Query("SELECT f.id FROM Family f WHERE f.deletedAt IS NULL ORDER BY f.id")
    List<Long> findAllActiveIds();

    /**
     * 查找某用户创建的所有家庭（用于 OWNER 资产核对）。
     */
    List<Family> findByCreatedBy(Long createdBy);

    /**
     * 按 ID 查未被软删除的家庭（deleteFamily 后查询自动过滤掉）。
     */
    Optional<Family> findByIdAndDeletedAtIsNull(Long id);

    /** 家庭成员、权限与邀请写操作共用行锁，在读取业务状态之前获取。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Family f WHERE f.id = :id AND f.deletedAt IS NULL")
    Optional<Family> findActiveByIdForUpdate(Long id);

    /**
     * 批量查未删除家庭（用户我的家庭列表用，过滤掉已解散家庭）。
     */
    List<Family> findByIdInAndDeletedAtIsNull(Iterable<Long> ids);
}
