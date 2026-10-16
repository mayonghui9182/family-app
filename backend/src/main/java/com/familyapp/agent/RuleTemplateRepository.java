package com.familyapp.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/** 统一模板数据访问（C-11 列表 / C-12 few-shot 关联 / C-15 routine 匹配 / C-18 版本事务）。 */
public interface RuleTemplateRepository extends JpaRepository<RuleTemplate, Long> {

    /** 家庭内全部模板（C-11 不带过滤时）。 */
    List<RuleTemplate> findByFamilyIdOrderByIdAsc(Long familyId);

    /** 按类型 + 启用态过滤（C-11 query 参数）。 */
    List<RuleTemplate> findByFamilyIdAndTypeOrderByIdAsc(Long familyId, String type);

    List<RuleTemplate> findByFamilyIdAndIsActiveOrderByIdAsc(Long familyId, Boolean isActive);

    List<RuleTemplate> findByFamilyIdAndTypeAndIsActiveOrderByIdAsc(Long familyId, String type, Boolean isActive);

    /** C-15：家庭内启用的 routine 模板。 */
    List<RuleTemplate> findByFamilyIdAndTypeAndIsActiveTrue(Long familyId, String type);

    /** 拥有启用 routine 模板的家庭 id 列表（2:00 日常调度逐家庭遍历）。 */
    @Query("SELECT DISTINCT t.familyId FROM RuleTemplate t WHERE t.type = 'routine' AND t.isActive = true")
    List<Long> findDistinctFamilyIdsWithActiveRoutine();

    /** family_id 作用域限定查找。 */
    Optional<RuleTemplate> findByIdAndFamilyId(Long id, Long familyId);
}
