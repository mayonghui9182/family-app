package com.familyapp.wechat;

import com.familyapp.wechat.WechatNotifyDtos.KefuBatchRequest;
import com.familyapp.wechat.WechatNotifyDtos.KefuTextMessage;
import com.familyapp.wechat.WechatNotifyDtos.WechatNotifyBatchRequest;
import com.familyapp.wechat.WechatNotifyDtos.WechatNotifyItemResult;
import com.familyapp.wechat.WechatNotifyDtos.WechatNotifyMessage;
import com.familyapp.wechat.WechatNotifyDtos.WechatNotifyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * backend → wechat-service 推送客户端（Change 12 模板消息 + Change 14 Task 8 客服消息，design D6）。
 *
 * <p>失败隔离铁律：推送是业务旁路，连接超时 2s / 读超时 5s，任何传输/解析异常
 * 或非 2xx 响应都只记 warn 并返回空结果，<b>绝不抛出</b>；
 * {@code wechat.notify.enabled=false} 时零 HTTP 调用。
 */
@Component
public class WechatNotifyClient {

    private static final Logger log = LoggerFactory.getLogger(WechatNotifyClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final boolean enabled;
    private final String internalSecret;

    @Autowired
    public WechatNotifyClient(
            @Value("${wechat.service-base-url:http://wechat-service:8900}") String baseUrl,
            @Value("${wechat.notify.enabled:true}") boolean enabled,
            @Value("${internal-api.secret:}") String internalSecret) {
        this(buildTemplate(), baseUrl, enabled, internalSecret);
    }

    /** 测试构造器：注入 RestTemplate 以挂 MockRestServiceServer。 */
    WechatNotifyClient(RestTemplate restTemplate, String baseUrl, boolean enabled, String internalSecret) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.enabled = enabled;
        this.internalSecret = internalSecret;
    }

    private static RestTemplate buildTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    /**
     * 发送一条客服文本（Change 14 Task 8，design.md D6）。
     *
     * <p>沿用 {@link #sendMessages} 的失败隔离铁律：{@code wechat.notify.enabled=false} 或参数空白时
     * 零 HTTP；传输异常、非 2xx、微信侧拒绝（如 45015「超出 48 小时被动回复窗口」）都只记 warn
     * 并返回 false，<b>绝不抛出</b>——回推是业务旁路，不许改动拆解作业的终态。
     *
     * @return 该条是否真正送达微信；调用方只用于日志，不据此重试（推送失败不重试是 D6 的既定取舍）
     */
    public boolean sendKefuText(String openid, String content) {
        if (!enabled || isBlank(openid) || isBlank(content)) {
            return false;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Secret", internalSecret);
            ResponseEntity<WechatNotifyResponse> resp = restTemplate.postForEntity(
                    baseUrl + "/internal/kefu/send",
                    new HttpEntity<>(new KefuBatchRequest(
                            List.of(new KefuTextMessage(openid, content.strip()))), headers),
                    WechatNotifyResponse.class);
            if (resp.getStatusCode().is2xxSuccessful()
                    && resp.getBody() != null && resp.getBody().results() != null) {
                List<WechatNotifyItemResult> results = resp.getBody().results();
                if (results.isEmpty()) {
                    log.warn("微信客服消息接口未返回逐条结果，按未送达处理：openid={}", openid);
                    return false;
                }
                if (results.get(0).ok()) {
                    return true;
                }
                log.warn("微信客服消息未送达（已隔离，多为超出 48 小时窗口或用户未关注）："
                        + "openid={} errcode={} errmsg={}",
                        openid, results.get(0).errcode(), results.get(0).errmsg());
                return false;
            }
            log.warn("微信客服消息接口非成功响应：{}", resp.getStatusCode());
        } catch (Exception e) {
            log.warn("微信客服消息发送失败（已隔离，不影响业务）：{}", e.getMessage());
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 持久化 outbox 使用逐条错误码；日志不输出 openid、正文或远端错误正文。 */
    public WechatNotifyItemResult sendKefuTextDetailed(String openid, String content) {
        if (!enabled || isBlank(openid) || isBlank(content)) {
            return new WechatNotifyItemResult(null, false, true, null, "SEND_DISABLED");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Secret", internalSecret);
            var response = restTemplate.postForEntity(baseUrl + "/internal/kefu/send",
                    new HttpEntity<>(new KefuBatchRequest(List.of(new KefuTextMessage(openid, content))), headers),
                    WechatNotifyResponse.class);
            if (response.getBody() != null && response.getBody().results() != null
                    && response.getBody().results().size() == 1) {
                var result = response.getBody().results().get(0);
                // 不允许错误的收件人响应被当成当前消息发送成功。
                if (openid.equals(result.openid())) {
                    return new WechatNotifyItemResult(null, result.ok(), result.skipped(), result.errcode(), null);
                }
            }
            return new WechatNotifyItemResult(null, false, false, null, "INVALID_SEND_RESPONSE");
        } catch (Exception exception) {
            log.warn("微信客服 outbox 调用失败 error_type={}", exception.getClass().getSimpleName());
            return new WechatNotifyItemResult(null, false, false, null, "TRANSPORT_ERROR");
        }
    }

    /**
     * 批量发送模板消息。任何失败返回空列表（不抛出）；成功返回逐条结果。
     */
    public List<WechatNotifyItemResult> sendMessages(List<WechatNotifyMessage> messages) {
        if (!enabled || messages == null || messages.isEmpty()) {
            return List.of();
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Secret", internalSecret);
            ResponseEntity<WechatNotifyResponse> resp = restTemplate.postForEntity(
                    baseUrl + "/internal/template/send",
                    new HttpEntity<>(new WechatNotifyBatchRequest(messages), headers),
                    WechatNotifyResponse.class);
            if (resp.getStatusCode().is2xxSuccessful()
                    && resp.getBody() != null && resp.getBody().results() != null) {
                List<WechatNotifyItemResult> results = resp.getBody().results();
                long failed = results.stream().filter(r -> !r.ok()).count();
                if (failed > 0) {
                    log.warn("微信模板消息批量发送完成：{}/{} 条失败（含取关/未配置模板，已隔离）",
                            failed, results.size());
                }
                return results;
            }
            log.warn("微信模板消息接口非成功响应：{}", resp.getStatusCode());
        } catch (Exception e) {
            log.warn("微信模板消息发送失败（已隔离，不影响业务）：{}", e.getMessage());
        }
        return List.of();
    }
}
