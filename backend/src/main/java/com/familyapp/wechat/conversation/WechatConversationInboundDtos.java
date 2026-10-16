package com.familyapp.wechat.conversation;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** 公众号持续会话入站接口的内部 DTO。 */
public final class WechatConversationInboundDtos {

    private WechatConversationInboundDtos() {
    }

    /** 被动回复只表达可靠接收结果，不在同步请求中执行 AI。 */
    public enum PassiveReplyType {
        RECEIVED,
        BIND_REQUIRED,
        RESULT_AVAILABLE
    }

    public record MessageRequest(
            @JsonProperty("wechat_account_id")
            @NotBlank(message = "wechat_account_id 不能为空")
            @Size(max = 100, message = "wechat_account_id 不能超过 100 个字符")
            String wechatAccountId,

            @NotBlank(message = "openid 不能为空")
            @Size(max = 64, message = "openid 不能超过 64 个字符")
            String openid,

            @JsonProperty("msg_id")
            @NotBlank(message = "msg_id 不能为空")
            @Size(max = 100, message = "msg_id 不能超过 100 个字符")
            String msgId,

            @NotNull(message = "content 不能为空")
            String content,

            @JsonProperty("received_at")
            @NotNull(message = "received_at 不能为空")
            Instant receivedAt
    ) {
    }

    public record MessageResponse(
            @JsonProperty("message_id") Long messageId,
            boolean persisted,
            boolean duplicate,
            @JsonProperty("reply_type") PassiveReplyType replyType,
            @JsonProperty("reply_text") String replyText
    ) {
        public MessageResponse(Long messageId, boolean persisted, boolean duplicate, PassiveReplyType replyType) {
            this(messageId, persisted, duplicate, replyType, null);
        }
    }
}
