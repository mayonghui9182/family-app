package com.familyapp.auth.recovery;

import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class PasswordResetTokens {
    private final UserRepository users;
    private final PasswordResetRepository resets;
    private final PasswordEncoder encoder;
    private final SecureRandom random = new SecureRandom();
    public PasswordResetTokens(UserRepository users, PasswordResetRepository resets, PasswordEncoder encoder) {
        this.users = users; this.resets = resets; this.encoder = encoder;
    }
    public record Delivery(String email, String token) {}

    @Transactional
    public Delivery issue(String email) {
        var user = users.lockActiveByEmail(email).orElse(null);
        if (user == null) return null;
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        var reset = resets.findById(user.getId()).orElseGet(PasswordReset::new);
        reset.userId = user.getId(); reset.tokenHash = hash(token);
        reset.expiresAt = Instant.now().plusSeconds(900); reset.usedAt = null;
        resets.save(reset);
        return new Delivery(user.getEmail(), token);
    }

    @Transactional
    public void consume(String token, String password) {
        if (token == null || !token.matches("[0-9a-f]{64}")) throw invalid();
        var found = resets.findByTokenHash(hash(token)).orElseThrow(this::invalid);
        var user = users.lockActiveById(found.userId).orElseThrow(this::invalid);
        // 加锁后重新读取数据库状态，避免一级缓存返回并发消费前的旧对象。
        var current = resets.currentState(user.getId()).orElseThrow(this::invalid);
        if (!current.getTokenHash().equals(hash(token)) || current.getUsedAt() != null
                || !current.getExpiresAt().isAfter(Instant.now())) throw invalid();
        user.setPasswordHash(encoder.encode(password));
        user.setAccessToken(null); user.setTokenUpdatedAt(null); user.setUpdatedAt(Instant.now());
        resets.markUsed(user.getId(), Instant.now());
    }
    private ApiException invalid() { return new ApiException(ResultCode.PASSWORD_RESET_INVALID); }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("缺少摘要算法"); }
    }
}
