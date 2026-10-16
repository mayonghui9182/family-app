package com.familyapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 安全配置：提供密码加密器 Bean。
 * <p>仅引入 spring-security-crypto 模块的 BCryptPasswordEncoder，
 * 不依赖 Spring Security Web 过滤器链，避免对现有路由造成非预期拦截。</p>
 */
@Configuration
public class SecurityConfig {

    /**
     * BCrypt 密码加密器（默认强度 10）。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
