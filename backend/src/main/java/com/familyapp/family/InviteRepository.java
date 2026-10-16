package com.familyapp.family;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 邀请码数据访问层。
 */
@Repository
public interface InviteRepository extends JpaRepository<Invite, Long> {

    /** 只定位家庭，不提前加载邀请实体，防止等待家庭锁后仍读取一级缓存中的旧次数。 */
    @Query("SELECT i.familyId FROM Invite i WHERE i.code = :code AND i.status = :status")
    Optional<Long> findFamilyIdByCodeAndStatus(String code, InviteStatus status);

    Optional<Invite> findByFamilyIdAndCodeAndStatus(Long familyId, String code, InviteStatus status);

    /**
     * 按邀请码 + 状态精确查找（POST /api/invites/accept 使用）。
     * 匹配 partial unique index uk_invites_active_code (WHERE status = 'ACTIVE')。
     */
    Optional<Invite> findByCodeAndStatus(String code, InviteStatus status);

    /**
     * 查找家庭下所有邀请码（可选状态过滤，OWNER/ADULT 查看用）。
     */
    List<Invite> findAllByFamilyId(Long familyId);

    /**
     * 查找家庭下特定状态的邀请码。
     */
    List<Invite> findAllByFamilyIdAndStatus(Long familyId, InviteStatus status);
}
