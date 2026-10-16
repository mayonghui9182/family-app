package com.familyapp.wechat.conversation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 微信持续会话配置及切流安全校验。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WechatConversationProperties.class)
public class WechatConversationConfiguration {

    public WechatConversationConfiguration(
            WechatConversationProperties properties,
            @Value("${wechat.intake.enabled:false}") boolean legacyIntakeEnabled) {
        if (legacyIntakeEnabled && properties.isEnabled()) {
            throw new IllegalStateException(
                    "WECHAT_INTAKE_ENABLED 与 WECHAT_CONVERSATION_ENABLED 不能同时开启");
        }
    }
}
