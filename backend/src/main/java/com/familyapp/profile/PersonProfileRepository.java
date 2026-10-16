package com.familyapp.profile;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 人员档案 Repository
 *
 * 设计依据：plan Step 5 + spec §2 P-1/P-5
 * - JpaSpecificationExecutor 支持 P-1 family_id + profile_type 动态查询
 * - findByFamilyIdAndIdAndDeletedAtIsNull 单档查询（P-3 GET /{id}）
 * - findByFamilyIdAndUserId 查 owner 档案（FamilyService.createFamily 自动建档）
 * - existsByFamilyIdAndIdAndDeletedAtIsNull 用于 P-5 DELETE 前校验存在
 */
public interface PersonProfileRepository extends JpaRepository<PersonProfile, Long>, JpaSpecificationExecutor<PersonProfile> {

    /** P-1 列档案：按家庭查所有未删除档案 */
    List<PersonProfile> findByFamilyIdAndDeletedAtIsNull(Long familyId);

    /** P-1 列档案（带类型过滤）：按家庭 + 类型查未删除档案 */
    List<PersonProfile> findByFamilyIdAndProfileTypeAndDeletedAtIsNull(Long familyId, ProfileType profileType);

    /** P-3 详情 / P-5 DELETE 前校验：按家庭 + id 查单档（软删除隔离） */
    Optional<PersonProfile> findByFamilyIdAndIdAndDeletedAtIsNull(Long familyId, Long id);

    /** FamilyService.createFamily 自动建档：按家庭 + user_id 查 owner 档案（避免重复建档） */
    Optional<PersonProfile> findByFamilyIdAndUserIdAndDeletedAtIsNull(Long familyId, Long userId);

    /** TaskService.createTask 反查回填：按家庭 + user_id 查档案（assignee_id → assignee_profile_id） */
    Optional<PersonProfile> findByFamilyIdAndUserIdAndDeletedAtIsNullOrderByIdAsc(Long familyId, Long userId);
}
