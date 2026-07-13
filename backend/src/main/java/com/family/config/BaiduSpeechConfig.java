package com.family.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 百度语音配置类
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "baidu.speech")
public class BaiduSpeechConfig {

    /**
     * 应用ID
     */
    private String appId;

    /**
     * API Key
     */
    private String apiKey;

    /**
     * Secret Key
     */
    private String secretKey;

    /**
     * 语音识别接口地址
     */
    private String asrUrl = "https://vop.baidu.com/server_api";

    /**
     * 语音合成接口地址
     */
    private String ttsUrl = "https://tsn.baidu.com/text2audio";

    /**
     * 获取access_token接口地址
     */
    private String tokenUrl = "https://aip.baidubce.com/oauth/2.0/token";

    /**
     * 检查是否已配置API密钥
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isEmpty()
                && secretKey != null && !secretKey.isEmpty();
    }
}
