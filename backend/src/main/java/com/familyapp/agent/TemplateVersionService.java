package com.familyapp.agent;

import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-18 模板新版本事务服务：两步必须同事务——
 * ① INSERT rule_template_versions(version=当前+1, parent_version_id=当前最新行)
 * ② UPDATE rule_templates(version=新版本号, content=新内容, parent_version_id=新版本行 id)
 * 任一步失败整体回滚，模板与版本链保持一致。
 */
@Service
public class TemplateVersionService {

    private final RuleTemplateRepository templateRepository;
    private final RuleTemplateVersionRepository versionRepository;

    public TemplateVersionService(RuleTemplateRepository templateRepository,
                                  RuleTemplateVersionRepository versionRepository) {
        this.templateRepository = templateRepository;
        this.versionRepository = versionRepository;
    }

    /** 创建新版本并返回新版本号（当前版本 + 1）。 */
    @Transactional
    public int createNewVersion(Long templateId, Long familyId, JsonNode content,
                                String changeSummary, boolean isImportant) {
        RuleTemplate template = templateRepository.findByIdAndFamilyId(templateId, familyId)
                .orElseThrow(() -> new ApiException(ResultCode.TEMPLATE_NOT_FOUND));

        int currentVersion = template.getVersion() == null ? 1 : template.getVersion();
        RuleTemplateVersion latestRow =
                versionRepository.findTopByTemplateIdOrderByVersionDesc(templateId).orElse(null);

        RuleTemplateVersion row = new RuleTemplateVersion();
        row.setFamilyId(familyId);
        row.setTemplateId(templateId);
        row.setVersion(currentVersion + 1);
        row.setContent(content);
        row.setChangeSummary(changeSummary);
        row.setIsImportant(isImportant);
        if (latestRow != null) {
            row.setParentVersionId(latestRow.getId());
        }
        versionRepository.saveAndFlush(row);

        template.setVersion(currentVersion + 1);
        template.setContent(content);
        template.setParentVersionId(row.getId());
        templateRepository.save(template);

        return currentVersion + 1;
    }

    /**
     * C-21（Change 5 ❌2）：向最新版 change_summary 追加中文说明（\n 连接），
     * 不产生新版本；模板无版本行 → 46503。返回被追加版本的版本号。
     */
    @Transactional
    public int appendChangeSummary(Long templateId, Long familyId, String append) {
        templateRepository.findByIdAndFamilyId(templateId, familyId)
                .orElseThrow(() -> new ApiException(ResultCode.TEMPLATE_NOT_FOUND));
        RuleTemplateVersion latest = versionRepository
                .findTopByTemplateIdAndFamilyIdOrderByVersionDesc(templateId, familyId)
                .orElseThrow(() -> new ApiException(ResultCode.TEMPLATE_NO_VERSION));
        String existing = latest.getChangeSummary();
        latest.setChangeSummary(existing == null || existing.isBlank()
                ? append : existing + "\n" + append);
        versionRepository.save(latest);
        return latest.getVersion();
    }

    /**
     * C-24（Change 5）：读模板版本历史，最新版在前，limit 截断（1~50）。
     * diff 基线（AI 原始 common_steps）与 ❌2 幂等判定都要读 change_summary，
     * 而 rule_template_versions 的列级授权不在 agent_reader 迁移范围内 → 统一走本端点。
     */
    @Transactional(readOnly = true)
    public List<RuleTemplateVersion> recentVersions(Long templateId, Long familyId, int limit) {
        templateRepository.findByIdAndFamilyId(templateId, familyId)
                .orElseThrow(() -> new ApiException(ResultCode.TEMPLATE_NOT_FOUND));
        List<RuleTemplateVersion> rows = versionRepository.findByTemplateIdOrderByVersionDesc(templateId);
        int capped = Math.max(1, Math.min(limit, 50));
        return rows.size() <= capped ? rows : new ArrayList<>(rows.subList(0, capped));
    }

    /**
     * C-22（Change 5 版本保留策略）：删除「非重要 且 超过 30 天 且 非本模板最新 3 条」。
     * is_important=true 永久保留；每模板至少保留最近 3 个普通版本兜底。
     * 返回删除条数。
     */
    @Transactional
    public int cleanupExpiredVersions(Long familyId) {
        List<RuleTemplateVersion> candidates =
                versionRepository.findByFamilyIdAndIsImportantFalse(familyId);
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        Map<Long, List<RuleTemplateVersion>> byTemplate = candidates.stream()
                .sorted(Comparator.comparing(RuleTemplateVersion::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.groupingBy(RuleTemplateVersion::getTemplateId));
        List<RuleTemplateVersion> toDelete = new ArrayList<>();
        for (List<RuleTemplateVersion> versions : byTemplate.values()) {
            for (int i = 0; i < versions.size(); i++) {
                RuleTemplateVersion v = versions.get(i);
                if (i >= 3 && v.getCreatedAt() != null && v.getCreatedAt().isBefore(cutoff)) {
                    toDelete.add(v);
                }
            }
        }
        versionRepository.deleteAll(toDelete);
        return toDelete.size();
    }
}
