package com.familyapp.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 用户账号实体。对应 V4 DDL users 表。
 * 软删除：deleted_at != NULL 视为已删除（不参与邮箱/手机号唯一索引）。
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录邮箱（可为空，当且仅当手机号非空）。 */
    @Column(name = "email", length = 120)
    private String email;

    /** 登录手机号（可为空，当且仅当邮箱非空）。 */
    @Column(name = "phone", length = 20)
    private String phone;

    /** 显示昵称（必填，老人友好的称呼）。 */
    @Column(name = "nickname", length = 50, nullable = false)
    private String nickname;

    /** BCrypt 加密后的密码哈希（永远不明文存储）。 */
    @Column(name = "password_hash", length = 255, nullable = false)
    private String passwordHash;

    /** 头像 URL（可选）。 */
    @Column(name = "avatar", length = 255)
    private String avatar;

    /** 64 十六进制访问令牌（JWT-like 无状态 token）。 */
    @Column(name = "access_token", length = 64)
    private String accessToken;

    /** token 最近更新时间；超过 7 天视为过期（由 AuthInterceptor 判定）。 */
    @Column(name = "token_updated_at")
    private Instant tokenUpdatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 软删除时间戳，null = 未删除。 */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public Instant getTokenUpdatedAt() { return tokenUpdatedAt; }
    public void setTokenUpdatedAt(Instant tokenUpdatedAt) { this.tokenUpdatedAt = tokenUpdatedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}