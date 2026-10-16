package com.familyapp.wechat.conversation;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** 与聊天摘要分离、带版本和证据计数的家庭长期经验。 */
@Entity
@Table(name = "family_task_experiences", uniqueConstraints = {
        @UniqueConstraint(name = "uq_fte_id_family", columnNames = {"id", "family_id"}),
        @UniqueConstraint(name = "uq_fte_family_key_version",
                columnNames = {"family_id", "normalized_key", "version"})
})
public class FamilyTaskExperience {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false, updatable = false)
    private Long familyId;

    @Column(name = "normalized_key", nullable = false, length = 200, updatable = false)
    private String normalizedKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> valuePayload = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scope_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> scopePayload = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ExperienceStatus status = ExperienceStatus.CANDIDATE;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal confidence = BigDecimal.ZERO;

    @Column(name = "evidence_count", nullable = false)
    private Integer evidenceCount = 0;

    @Column(nullable = false, updatable = false)
    private Integer version;

    @Enumerated(EnumType.STRING)
    @Column(name = "activation_source", nullable = false, length = 24, updatable = false)
    private ExperienceActivationSource activationSource;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (valuePayload == null) valuePayload = new HashMap<>();
        if (scopePayload == null) scopePayload = new HashMap<>();
        if (status == null) status = ExperienceStatus.CANDIDATE;
        if (confidence == null) confidence = BigDecimal.ZERO;
        if (evidenceCount == null) evidenceCount = 0;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long value) { this.familyId = value; }
    public String getNormalizedKey() { return normalizedKey; }
    public void setNormalizedKey(String value) { this.normalizedKey = value; }
    public Map<String, Object> getValuePayload() { return valuePayload; }
    public void setValuePayload(Map<String, Object> value) { this.valuePayload = value; }
    public Map<String, Object> getScopePayload() { return scopePayload; }
    public void setScopePayload(Map<String, Object> value) { this.scopePayload = value; }
    public ExperienceStatus getStatus() { return status; }
    public void setStatus(ExperienceStatus value) { this.status = value; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal value) { this.confidence = value; }
    public Integer getEvidenceCount() { return evidenceCount; }
    public void setEvidenceCount(Integer value) { this.evidenceCount = value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { this.version = value; }
    public ExperienceActivationSource getActivationSource() { return activationSource; }
    public void setActivationSource(ExperienceActivationSource value) { this.activationSource = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

