package com.familyapp.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 模板版本历史数据访问（C-18 两步版本事务）。 */
public interface RuleTemplateVersionRepository extends JpaRepository<RuleTemplateVersion, Long> {

    /** 当前最大版本行（C-18 计算 version=当前+1）。 */
    Optional<RuleTemplateVersion> findTopByTemplateIdOrderByVersionDesc(Long templateId);

    List<RuleTemplateVersion> findByTemplateIdOrderByVersionDesc(Long templateId);

    /** C-21：指定模板+家庭的最新版本行（带家庭作用域）。 */
    Optional<RuleTemplateVersion> findTopByTemplateIdAndFamilyIdOrderByVersionDesc(
            Long templateId, Long familyId);

    /** C-22：该家庭所有非重要版本（保留策略候选）。 */
    List<RuleTemplateVersion> findByFamilyIdAndIsImportantFalse(Long familyId);

    /** C-29/30：按 id + 家庭查版本（家庭隔离）。 */
    Optional<RuleTemplateVersion> findByIdAndFamilyId(Long id, Long familyId);


    /** C-34：按家庭查全部版本（含重要）。 */
    List<RuleTemplateVersion> findByFamilyId(Long familyId);

}
