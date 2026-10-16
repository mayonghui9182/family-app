package com.familyapp.wechat.conversation;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 可确认的不可变候选快照；同一会话修订号只允许出现一次。 */
@Entity
@Table(name = "wechat_candidate_versions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_wcv_id_family", columnNames = {"id", "family_id"}),
        @UniqueConstraint(name = "uq_wcv_conversation_revision",
                columnNames = {"conversation_id", "revision"})
})
public class WechatCandidateVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private Long conversationId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private Long familyId;

    @Column(nullable = false, updatable = false)
    private Integer revision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "candidate_payload", nullable = false, columnDefinition = "jsonb", updatable = false)
    private List<Map<String, Object>> candidatePayload = new ArrayList<>();

    @Column(name = "rendered_preview", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String renderedPreview;

    @Column(name = "source_message_id", nullable = false, updatable = false)
    private Long sourceMessageId;

    @Column(name = "model_name", nullable = false, length = 100, updatable = false)
    private String modelName;

    @Column(name = "prompt_version", nullable = false, length = 100, updatable = false)
    private String promptVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "experience_refs", columnDefinition = "jsonb", updatable = false)
    private List<Map<String, Object>> experienceRefs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        if (candidatePayload == null) candidatePayload = new ArrayList<>();
    }

    public Long getId() { return id; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long value) { this.conversationId = value; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long value) { this.familyId = value; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer revision) { this.revision = revision; }
    public List<Map<String, Object>> getCandidatePayload() { return candidatePayload; }
    public void setCandidatePayload(List<Map<String, Object>> value) { this.candidatePayload = value; }
    public String getRenderedPreview() { return renderedPreview; }
    public void setRenderedPreview(String value) { this.renderedPreview = value; }
    public Long getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(Long value) { this.sourceMessageId = value; }
    public String getModelName() { return modelName; }
    public void setModelName(String value) { this.modelName = value; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String value) { this.promptVersion = value; }
    public List<Map<String, Object>> getExperienceRefs() { return experienceRefs; }
    public void setExperienceRefs(List<Map<String, Object>> value) { this.experienceRefs = value; }
    public Instant getCreatedAt() { return createdAt; }
}

