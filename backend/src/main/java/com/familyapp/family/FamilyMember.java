package com.familyapp.family;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 家庭成员关联实体。对应 V4 DDL family_members 表。
 * 每个 (family_id, user_id) 唯一；多次退出/重新加入时更新同一行。
 * left_at = null 代表当前活跃成员。
 */
@Entity
@Table(name = "family_members")
public class FamilyMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 成员角色（OWNER / ADULT / CHILD）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 10, nullable = false)
    private FamilyRole role;

    /** 加入家庭时间（邀请接受时刻或创建时刻）。 */
    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    /** 退出家庭时间戳；null 代表仍在家庭中。 */
    @Column(name = "left_at")
    private Instant leftAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long familyId) { this.familyId = familyId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public FamilyRole getRole() { return role; }
    public void setRole(FamilyRole role) { this.role = role; }
    public Instant getJoinedAt() { return joinedAt; }
    public void setJoinedAt(Instant joinedAt) { this.joinedAt = joinedAt; }
    public Instant getLeftAt() { return leftAt; }
    public void setLeftAt(Instant leftAt) { this.leftAt = leftAt; }
}