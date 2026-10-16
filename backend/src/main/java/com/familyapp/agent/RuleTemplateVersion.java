package com.familyapp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 模板版本历史快照实体（V6 rule_template_versions 表）。
 * C-18 创建新版本时 INSERT 本表（version=当前+1），(template_id, version) 唯一。
 */
@Entity
@Table(name = "rule_template_versions")
public class RuleTemplateVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    /** 版本号（同模板内唯一递增）。 */
    @Column(nullable = false)
    private Integer version;

    /** 该版本完整内容 JSONB 快照。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode content;

    /** 变更摘要（前端版本 diff 页展示，全中文）。 */
    @Column(name = "change_summary", columnDefinition = "TEXT")
    private String changeSummary;

    /** 是否重要版本（前端 ⭐ 高亮）。 */
    @Column(name = "is_important", nullable = false)
    private Boolean isImportant = Boolean.FALSE;

    /** 上一版本行 ID（首条为 NULL）。 */
    @Column(name = "parent_version_id")
    private Long parentVersionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = Instant.now();
        if (this.isImportant == null) this.isImportant = Boolean.FALSE;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long familyId) { this.familyId = familyId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public JsonNode getContent() { return content; }
    public void setContent(JsonNode content) { this.content = content; }
    public String getChangeSummary() { return changeSummary; }
    public void setChangeSummary(String changeSummary) { this.changeSummary = changeSummary; }
    public Boolean getIsImportant() { return isImportant; }
    public void setIsImportant(Boolean isImportant) { this.isImportant = isImportant; }
    public Long getParentVersionId() { return parentVersionId; }
    public void setParentVersionId(Long parentVersionId) { this.parentVersionId = parentVersionId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
