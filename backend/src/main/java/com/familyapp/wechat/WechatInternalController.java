package com.familyapp.wechat;

import com.familyapp.auth.dto.LoginResponse;
import com.familyapp.common.Result;
import com.familyapp.common.ResultCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 微信绑定域内部端点（Change 11，design.md D2/D6）。
 *
 * <p>路径前缀 /api/internal/wechat/**：由 InternalSecretInterceptor 守护
 * （X-Internal-Secret），AuthInterceptor 放行，仅供 wechat-service 机器调用。
 * <ul>
 *   <li>GET  /bind?openid= ：查绑定，未绑定返回 40115 业务码（HTTP 200，流程态非错误）；</li>
 *   <li>POST /ticket       ：为未绑定 openid 签发一次性绑定票据；</li>
 *   <li>POST /login        ：已绑定 openid 免密换 family-app token。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/internal/wechat")
public class WechatInternalController {

    private final WechatService wechatService;
    private final WechatTicketService ticketService;

    public WechatInternalController(WechatService wechatService,
                                    WechatTicketService ticketService) {
        this.wechatService = wechatService;
        this.ticketService = ticketService;
    }

    /** 查绑定：命中 → bound+userId；未绑定 → WECHAT_NOT_BOUND(40115) 业务码。 */
    @GetMapping("/bind")
    public Result<Map<String, Object>> getBind(@RequestParam("openid") String openid) {
        return wechatService.findBind(openid)
            .map(b -> Result.ok(Map.<String, Object>of(
                "bound", true,
                "userId", b.getUser().getId())))
            .orElse(Result.error(ResultCode.WECHAT_NOT_BOUND));
    }

    /** 签发一次性绑定票据（未绑定 OAuth 回调时 wechat-service 调用）。 */
    @PostMapping("/ticket")
    public Result<Map<String, String>> mintTicket(@RequestBody OpenidRequest req) {
        String ticket = ticketService.mint(req.openid());
        return Result.ok(Map.of("ticket", ticket));
    }

    /** 已绑定 openid 免密登录：返回与密码登录同形的 LoginResponse；未绑定 → 40115。 */
    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody OpenidRequest req) {
        return wechatService.loginByOpenid(req.openid())
            .map(Result::ok)
            .orElse(Result.error(ResultCode.WECHAT_NOT_BOUND));
    }

    /** 内部端点统一请求体：仅携带微信 openid。 */
    public record OpenidRequest(String openid) {}
}
