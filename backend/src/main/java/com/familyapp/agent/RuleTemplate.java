package com.familyapp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 统一模板实体（V6 rule_templates 表）：decomposition 分解模板 + routine 日常模板。
 *
 * <p>JSONB 字段映射说明：conditions/content 在 PG 上是 jsonb 列。采用 Hibernate 6
 * {@code @JdbcTypeCode(SqlTypes.JSON)} 映射 Jackson JsonNode（不用 String：Hibernate 6 会把字符串再当 JSON 文档编码，写库成转义字符串文档） —— 生产 PostgreSQL 方言生成/校验 jsonb，
 * 测试 H2 MODE=PostgreSQL 走原生 json 类型；内容由服务层用 Jackson 解析，
 * 不引入复杂自定义类型（JsonbMappingSmokeTest 已验证双方言往返）。</p>
 */
@Entity
@Table(name = "rule_templates")
public class RuleTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    /** 模板类型：decomposition（AI 拆解）| routine（日常触发）。 */
    @Column(nullable = false, length = 20)
    private String type;

    @Column(nullable = false, length = 100)
    private String name;

    /** 关联分类（可空）。 */
    @Column(name = "category_id")
    private Long categoryId;

    /**
     * Change 8 新列（V9.sql §2.2）：适配的档案类型数组。
     * S-1 similar_templates 匹配条件：NULL=全适配；非 NULL 时数组任一 profile_type 匹配即入选。
     * 统一用 JSON 列存储字符串数组（PG JSONB / H2 JSON），避免双方言原生 ARRAY 映射不稳定。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile_types")
    private java.util.List<String> profileTypes;

    /**
     * 触发条件 JSONB（routine 用）：weekly/monthly/weather/combo 四种结构；
     * decomposition 模板为 NULL。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conditions")
    private JsonNode conditions;

    /**
     * 模板内容 JSONB（NOT NULL）：分解步骤清单或 routine 产出任务的定义。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode content;

    /** 是否启用（停用模板不参与匹配与生成，对应错误码 46003）。 */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = Boolean.TRUE;

    /** 当前版本号，从 1 起；C-18 新版本事务内 +1。 */
    @Column(nullable = false)
    private Integer version = 1;

    /** 指向 rule_template_versions 当前版本行（循环外键，V6 建表后 ALTER 补）。 */
    @Column(name = "parent_version_id")
    private Long parentVersionId;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.isActive == null) this.isActive = Boolean.TRUE;
        if (this.version == null) this.version = 1;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long familyId) { this.familyId = familyId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public java.util.List<String> getProfileTypes() { return profileTypes; }
    public void setProfileTypes(java.util.List<String> profileTypes) { this.profileTypes = profileTypes; }
    public JsonNode getConditions() { return conditions; }
    public void setConditions(JsonNode conditions) { this.conditions = conditions; }
    public JsonNode getContent() { return content; }
    public void setContent(JsonNode content) { this.content = content; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Long getParentVersionId() { return parentVersionId; }
    public void setParentVersionId(Long parentVersionId) { this.parentVersionId = parentVersionId; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
