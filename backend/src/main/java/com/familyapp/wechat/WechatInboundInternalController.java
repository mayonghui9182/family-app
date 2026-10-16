package com.familyapp.wechat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.familyapp.common.Result;
import com.familyapp.common.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 微信入站消息受理内部端点（Change 14 Task 2）。
 *
 * <p>{@code POST /api/internal/wechat/inbound-message}，由 wechat-service 在收到公众号
 * 1v1 文本回调后机器调用。前缀 {@code /api/internal/**} 已被 {@code InternalSecretInterceptor}
 * 守护（缺失/错误秘钥 → HTTP 401 + 40101/40102），无需改动 {@code WebConfig}。
 *
 * <p>响应恒为 HTTP 200 + {@code Result{code:0,data:{accepted,reason}}}：
 * 「不受理」是业务裁决而非异常，让调用方无需分支处理 HTTP 状态码即可拿到中文话术线索。
 * 仅当请求体本身不可用（缺幂等键）时才返回 4xx。
 */
@RestController
@RequestMapping("/api/internal/wechat")
public class WechatInboundInternalController {

    private static final Logger log = LoggerFactory.getLogger(WechatInboundInternalController.class);

    private final WechatIntakeService intakeService;

    public WechatInboundInternalController(WechatIntakeService intakeService) {
        this.intakeService = intakeService;
    }

    /**
     * 入站消息请求体。字段名沿用 backend ↔ agent-service 的 snake_case 内部契约，
     * 便于 wechat-service 直接透传微信回调字段。
     */
    record InboundRequest(
        String openid,
        @JsonProperty("msg_id") String msgId,
        String content,
        @JsonProperty("received_at") String receivedAt
    ) {}

    @PostMapping("/inbound-message")
    public ResponseEntity<Result<WechatIntakeService.Verdict>> inboundMessage(
        @RequestBody(required = false) InboundRequest request
    ) {
        if (request == null || isBlank(request.msgId())) {
            // msg_id 是幂等键，缺失则无法去重，会让重推撞 UNIQUE 抛 500，必须挡在落库前
            return ResponseEntity.badRequest()
                .body(Result.error(ResultCode.BAD_REQUEST_VALIDATION, "msg_id 不能为空"));
        }
        WechatIntakeService.Verdict verdict = intakeService.accept(
            request.openid(), request.msgId(), request.content(), parseReceivedAt(request.receivedAt()));
        return ResponseEntity.ok(Result.ok(verdict));
    }

    private Instant parseReceivedAt(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        try {
            return Instant.parse(raw.strip());
        } catch (RuntimeException e) {
            // 时间格式异常不该让一条消息进不了队列，退回服务端当前时间并留痕
            log.warn("入站消息 received_at 格式不可解析，改用服务端时间：{}", raw);
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
