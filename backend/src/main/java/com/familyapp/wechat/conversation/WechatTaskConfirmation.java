package com.familyapp.wechat.conversation;

import jakarta.persistence.*;

import java.time.Instant;

/** 一次精确候选确认的不可重复事务凭据。 */
@Entity
@Table(name = "wechat_task_confirmations", uniqueConstraints = {
        @UniqueConstraint(name = "uq_wtcf_id_family", columnNames = {"id", "family_id"}),
        @UniqueConstraint(name = "uq_wtcf_candidate", columnNames = "candidate_version_id"),
        @UniqueConstraint(name = "uq_wtcf_idempotency", columnNames = "idempotency_key"),
        @UniqueConstraint(name = "uq_wtcf_confirmation_message", columnNames = "confirmation_message_id")
})
public class WechatTaskConfirmation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private Long conversationId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private Long familyId;

    /** 清理候选正文前允许显式置空，确认哈希与修订号继续保留。 */
    @Column(name = "candidate_version_id")
    private Long candidateVersionId;

    @Column(name = "candidate_revision", nullable = false, updatable = false)
    private Integer candidateRevision;

    @Column(name = "candidate_hash", nullable = false, length = 64, updatable = false)
    private String candidateHash;

    /** 清理原始消息前允许显式置空。 */
    @Column(name = "confirmation_message_id")
    private Long confirmationMessageId;

    @Column(name = "confirmed_by", nullable = false, updatable = false)
    private Long confirmedBy;

    @Column(name = "idempotency_key", nullable = false, length = 200, updatable = false)
    private String idempotencyKey;

    @Column(name = "committed_at", nullable = false, updatable = false)
    private Instant committedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (committedAt == null) committedAt = now;
        createdAt = now;
    }

    public Long getId() { return id; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long value) { this.conversationId = value; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long value) { this.familyId = value; }
    public Long getCandidateVersionId() { return candidateVersionId; }
    public void setCandidateVersionId(Long value) { this.candidateVersionId = value; }
    public Integer getCandidateRevision() { return candidateRevision; }
    public void setCandidateRevision(Integer value) { this.candidateRevision = value; }
    public String getCandidateHash() { return candidateHash; }
    public void setCandidateHash(String value) { this.candidateHash = value; }
    public Long getConfirmationMessageId() { return confirmationMessageId; }
    public void setConfirmationMessageId(Long value) { this.confirmationMessageId = value; }
    public Long getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(Long value) { this.confirmedBy = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { this.idempotencyKey = value; }
    public Instant getCommittedAt() { return committedAt; }
    public void setCommittedAt(Instant value) { this.committedAt = value; }
    public Instant getCreatedAt() { return createdAt; }
}

