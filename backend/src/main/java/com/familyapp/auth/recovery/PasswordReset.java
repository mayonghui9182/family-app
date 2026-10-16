package com.familyapp.auth.recovery;

import jakarta.persistence.*;
import java.time.Instant;

/** 每个账号最多保存一份重置摘要，原文仅在发送邮件时短暂存在内存中。 */
@Entity
@Table(name = "password_resets")
public class PasswordReset {
    @Id @Column(name = "user_id") Long userId;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) String tokenHash;
    @Column(name = "expires_at", nullable = false) Instant expiresAt;
    @Column(name = "used_at") Instant usedAt;
}
