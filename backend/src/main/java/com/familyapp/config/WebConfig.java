package com.familyapp.config;

import com.familyapp.auth.AuthInterceptor;
import com.familyapp.family.FamilyContextInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnProperty(name = "familyapp.auth.enabled", havingValue = "true", matchIfMissing = true)
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final FamilyContextInterceptor familyContextInterceptor;
    private final InternalSecretInterceptor internalSecretInterceptor;

    public WebConfig(AuthInterceptor authInterceptor,
                     FamilyContextInterceptor familyContextInterceptor,
                     InternalSecretInterceptor internalSecretInterceptor) {
        this.authInterceptor = authInterceptor;
        this.familyContextInterceptor = familyContextInterceptor;
        this.internalSecretInterceptor = internalSecretInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 内部 Agent API 秘钥校验必须注册在最前：agent-service 是机器调用、不携带用户 Token，
        // 若排在 AuthInterceptor 之后，/api/internal/** 会先被登录鉴权拒掉（40100「请先登录后再操作」）。
        registry.addInterceptor(internalSecretInterceptor).addPathPatterns("/api/internal/**");
        // 登录鉴权排除内部 Agent API：agent-service 是机器调用、不携带用户 Token，
        // 秘钥校验通过后不应再被登录鉴权拦截（否则一律 40100「请先登录后再操作」）。
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**")
                .excludePathPatterns("/api/internal/**", "/api/agent/v1/config");
        registry.addInterceptor(familyContextInterceptor).addPathPatterns("/api/**");
    }
}
