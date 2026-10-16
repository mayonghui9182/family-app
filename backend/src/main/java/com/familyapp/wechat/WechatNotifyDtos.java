package com.familyapp.wechat;

import java.util.List;
import java.util.Map;

/**
 * 微信推送内部契约 DTO（backend → wechat-service）。
 *
 * <p>模板消息（Change 12，POST /internal/template/send）与客服消息（Change 14 Task 8，
 * POST /internal/kefu/send）共用逐条结果响应形状。字段名与 wechat-service 的请求/响应 JSON 一一对应。
 */
public final class WechatNotifyDtos {

    private WechatNotifyDtos() {
    }

    /** 单条消息：openid + 事件类型 + 业务参数（渲染细节在 wechat-service）。 */
    public record WechatNotifyMessage(String openid, String event, Map<String, Object> params) {
    }

    /** 批量请求体。 */
    public record WechatNotifyBatchRequest(List<WechatNotifyMessage> messages) {
    }

    /** 单条发送结果（wechat-service 逐条返回）。 */
    public record WechatNotifyItemResult(
            String openid, boolean ok, Boolean skipped, Integer errcode, String errmsg) {
    }

    /** 批量响应体。 */
    public record WechatNotifyResponse(Integer code, List<WechatNotifyItemResult> results) {
    }

    /** 客服消息单条（Change 14 Task 8）：openid + 中文纯文本正文。 */
    public record KefuTextMessage(String openid, String content) {
    }

    /** 客服消息批量请求体，字段名与 wechat-service 的 {@code messages[]} 对齐。 */
    public record KefuBatchRequest(List<KefuTextMessage> messages) {
    }
}
