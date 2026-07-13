package com.family.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 配置类
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtConfig {

    /**
     * 密钥
     */
    private String secret = "family-management-system-secret-key-2024";

    /**
     * 过期时间（小时）
     */
    private Integer expireHours = 24;

    /**
     * 获取签名密钥
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 生成 JWT Token
     *
     * @param userId   用户ID
     * @param familyId 家庭ID
     * @param role     角色
     * @return JWT Token
     */
    public String generateToken(Long userId, Long familyId, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expireHours * 3600 * 1000L);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("familyId", familyId)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 解析 Token 获取 Claims
     *
     * @param token JWT Token
     * @return Claims
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 验证 Token 是否有效
     *
     * @param token JWT Token
     * @return 是否有效
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
