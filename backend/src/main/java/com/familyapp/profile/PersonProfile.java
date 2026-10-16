package com.familyapp.profile;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 人员档案实体（person_profiles 表映射）
 *
 * 设计依据：plan Step 5 + V9.sql §1.1 DDL
 * - @Where 软删除隔离（与 Category 同款）
 * - profile_data + tags 用 @JdbcTypeCode(SqlTypes.JSON) 映射 JSONB（V6 rule_templates.content 同款）
 * - profile_type 用 @Enumerated(STRING) 映射 VARCHAR(10)
 * - @PrePersist/@PreUpdate 时间戳兜底（与 Category 同款）
 */
@Entity
@Table(name = "person_profiles")
@Where(clause = "deleted_at IS NULL")
public class PersonProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "profile_type", nullable = false, length = 10)
    private ProfileType profileType;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "avatar_emoji", nullable = false, length = 8)
    private String avatarEmoji;

    @Column(length = 6)
    private String gender;

    @Column(name = "birthday")
    private LocalDate birthday;

    @Column(nullable = false, length = 20)
    private String relation;

    /**
     * 人员档案标签：统一 JSON 列存储字符串数组（PG JSONB / H2 JSON），
     * 避免 Hibernate @Array + H2 / PG 双方言 VARCHAR ARRAY 映射不稳定。
     * 与 profile_data（Map<String,Object> 存 JSONB）同款策略。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", nullable = false)
    private List<String> tags = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile_data", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> profileData = new HashMap<>();

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.tags == null) this.tags = List.of();
        if (this.profileData == null) this.profileData = new HashMap<>();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ===== getter/setter =====

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long familyId) { this.familyId = familyId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public ProfileType getProfileType() { return profileType; }
    public void setProfileType(ProfileType profileType) { this.profileType = profileType; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAvatarEmoji() { return avatarEmoji; }
    public void setAvatarEmoji(String avatarEmoji) { this.avatarEmoji = avatarEmoji; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public LocalDate getBirthday() { return birthday; }
    public void setBirthday(LocalDate birthday) { this.birthday = birthday; }
    public String getRelation() { return relation; }
    public void setRelation(String relation) { this.relation = relation; }
    /** tags 以 JSON 数组存储；Getter 返回自身 List<String> */
    public List<String> getTags() { return tags; }
    /** 接受 List<String> 写入 tags；NULL 归一化为空列表。 */
    public void setTags(List<String> tags) { this.tags = tags == null || tags.isEmpty() ? List.of() : new ArrayList<>(tags); }
    public Map<String, Object> getProfileData() { return profileData; }
    public void setProfileData(Map<String, Object> profileData) { this.profileData = profileData; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}
