package com.familyapp.family;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 家庭成员关联数据访问层。
 * 核心查询都带 left_at IS NULL 谓词，仅匹配"当前活跃成员"。
 */
@Repository
public interface FamilyMemberRepository extends JpaRepository<FamilyMember, Long> {

    /**
     * 校验某用户是否为某家庭的活跃成员（left_at IS NULL）。
     * 用于 X-Family-Id 权限拦截和所有需要成员资格断言的场景。
     */
    Optional<FamilyMember> findByFamilyIdAndUserIdAndLeftAtIsNull(Long familyId, Long userId);

    /**
     * 列出家庭内的所有活跃成员（用于 GET /api/families/{id} 详情响应）。
     * 对应 partial index idx_fm_active。
     */
    List<FamilyMember> findAllByFamilyIdAndLeftAtIsNull(Long familyId);

    /**
     * 列出用户当前所属的所有活跃家庭（用于登录响应 families[] 列表）。
     */
    List<FamilyMember> findAllByUserIdAndLeftAtIsNull(Long userId);

    /**
     * 家庭内所有成员（含已退出的，用于转让 OWNER 时校验目标是否是曾加入过本家庭的历史成员——可扩展）。
     */
    List<FamilyMember> findAllByFamilyId(Long familyId);
}