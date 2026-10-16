package com.familyapp.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * AI 拆解会话 Repository（agent_decompose_sessions 表访问）
 *
 * <p>设计依据：plan Task 1 Step 5c + V9.sql 索引
 * <ul>
 *   <li>idx_ads_family_status(family_id, status)：D-1 列表 / B-3 过滤</li>
 *   <li>idx_ads_profile(profile_id)：B-4 / D-5 按档案查会话</li>
 *   <li>idx_ads_created_by(created_by)：会话作者查询</li>
 * </ul>
 *
 * <p>B-5/B-6 InternalAgentBGroupTest 直接用 findById() 读写 ACTIVE 会话；
 * Task 2 DecomposeSessionService 再按业务加方法（如 findByFamilyIdAndIdAndStatusIn）。
 */
public interface DecomposeSessionRepository extends JpaRepository<DecomposeSession, Long> {

    /** 写会话先锁定同家庭行，防重复确认和迟到草稿覆盖终态。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from DecomposeSession s where s.id = :id and s.familyId = :familyId")
    Optional<DecomposeSession> findLockedScoped(@org.springframework.data.repository.query.Param("id") Long id,
            @org.springframework.data.repository.query.Param("familyId") Long familyId);

    /** B-5/B-6：按 id + status=ACTIVE 查；找不到抛业务错（防 404+ 终态再写） */
    Optional<DecomposeSession> findByIdAndStatus(Long id, DecomposeSessionStatus status);

    /** Task 2 D-5/D-6/D-7：多租户按 id + familyId 查会话；不存在 → 46001 */
    Optional<DecomposeSession> findByIdAndFamilyId(Long id, Long familyId);
}
