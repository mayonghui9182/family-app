package com.familyapp.wechat.conversation;

import jakarta.persistence.*;

import java.time.Instant;

/** 一条可追溯、按独立性键去重的家庭经验支持证据。 */
@Entity
@Table(name = "family_task_experience_evidence",
        uniqueConstraints = @UniqueConstraint(name = "uq_ftee_independence",
                columnNames = {"family_id", "experience_id", "independence_key"}))
public class FamilyTaskExperienceEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false, updatable = false)
    private Long familyId;

    @Column(name = "experience_id", nullable = false, updatable = false)
    private Long experienceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 24, updatable = false)
    private ExperienceEvidenceSourceType sourceType;

    @Column(name = "source_id", nullable = false, length = 200, updatable = false)
    private String sourceId;

    @Column(name = "independence_key", nullable = false, length = 200, updatable = false)
    private String independenceKey;

    @Column(name = "content_hash", nullable = false, length = 64, updatable = false)
    private String contentHash;

    @Column(name = "observed_at", nullable = false, updatable = false)
    private Instant observedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (observedAt == null) observedAt = now;
        createdAt = now;
    }

    public Long getId() { return id; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long value) { this.familyId = value; }
    public Long getExperienceId() { return experienceId; }
    public void setExperienceId(Long value) { this.experienceId = value; }
    public ExperienceEvidenceSourceType getSourceType() { return sourceType; }
    public void setSourceType(ExperienceEvidenceSourceType value) { this.sourceType = value; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String value) { this.sourceId = value; }
    public String getIndependenceKey() { return independenceKey; }
    public void setIndependenceKey(String value) { this.independenceKey = value; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String value) { this.contentHash = value; }
    public Instant getObservedAt() { return observedAt; }
    public void setObservedAt(Instant value) { this.observedAt = value; }
    public Instant getCreatedAt() { return createdAt; }
}

