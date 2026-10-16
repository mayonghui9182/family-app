package com.familyapp.wechat.conversation;

import com.familyapp.common.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.familyapp.wechat.conversation.WechatConversationInboundDtos.MessageRequest;
import static com.familyapp.wechat.conversation.WechatConversationInboundDtos.MessageResponse;

/** 由 wechat-service 经内部密钥调用的持续会话入站端点。 */
@RestController
@RequestMapping("/api/internal/wechat/conversation")
public class WechatConversationInboundController {

    private final WechatConversationInboundService inboundService;

    public WechatConversationInboundController(WechatConversationInboundService inboundService) {
        this.inboundService = inboundService;
    }

    @PostMapping("/messages")
    public Result<MessageResponse> accept(@Valid @RequestBody MessageRequest request) {
        return Result.ok(inboundService.accept(request));
    }
}
