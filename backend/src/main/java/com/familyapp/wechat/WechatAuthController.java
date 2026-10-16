package com.familyapp.wechat;

import com.familyapp.auth.dto.LoginResponse;
import com.familyapp.common.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信绑定页公开端点（Change 11，design.md D3）。
 *
 * <p>挂在 /api/auth/** 前缀下（AuthInterceptor 公开路径，与注册/登录同款放行），
 * 供 H5 绑定页浏览器直接调用：提交一次性票据 + 邮箱/密码，
 * 成功后返回与密码登录同形的 LoginResponse（token 由前端存入，openid 永不到达前端）。
 * 票据失效 → 40116 引导从公众号菜单重进；密码错 → 40113，票据保留可重试。
 */
@RestController
@RequestMapping("/api/auth")
public class WechatAuthController {

    private final WechatService wechatService;

    public WechatAuthController(WechatService wechatService) {
        this.wechatService = wechatService;
    }

    @PostMapping("/wechat-bind")
    public Result<LoginResponse> bind(@RequestBody WechatBindRequest req) {
        LoginResponse resp = wechatService.bindByTicket(req.ticket(), req.email(), req.password());
        return Result.ok(resp);
    }

    /** 绑定页提交体：票据（URL query 带来）+ 邮箱 + 密码。 */
    public record WechatBindRequest(String ticket, String email, String password) {}
}
