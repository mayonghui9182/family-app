package com.familyapp.wechat;

import com.familyapp.auth.AuthService;
import com.familyapp.auth.dto.LoginRequest;
import com.familyapp.auth.dto.LoginResponse;
import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.user.User;
import com.familyapp.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 微信绑定/免密登录领域服务（Change 11，design.md D2/D3）。
 *
 * <p>绑定数据归 backend 所有；wechat-service 无 DB，仅通过内部端点调用本服务。
 * openid 全程服务端流转，票据是前端唯一可见的不透明凭证。
 */
@Service
public class WechatService {

    private final WechatBindRepository bindRepository;
    private final WechatTicketService ticketService;
    private final AuthService authService;
    private final UserRepository userRepository;

    public WechatService(WechatBindRepository bindRepository,
                         WechatTicketService ticketService,
                         AuthService authService,
                         UserRepository userRepository) {
        this.bindRepository = bindRepository;
        this.ticketService = ticketService;
        this.authService = authService;
        this.userRepository = userRepository;
    }

    /** 按 openid 查绑定关系（内部端点用）。 */
    @Transactional(readOnly = true)
    public Optional<WechatBind> findBind(String openid) {
        if (openid == null || openid.isBlank()) {
            return Optional.empty();
        }
        return bindRepository.findByOpenid(openid);
    }

    /**
     * 绑定页提交：票据 + 邮箱/密码 → 验证凭证 → 消费票据 → 幂等建绑 → 返回登录态。
     *
     * <p>顺序保证（design.md D3 + tasks Task1）：
     * <ol>
     *   <li>票据只读校验：伪造/过期 → 40116，不泄露账号信息；</li>
     *   <li>凭证校验：密码错 → 40113，<b>票据不消费</b>，用户改密码后可重试；</li>
     *   <li>CAS 消费票据：单次有效，重复/并发 → 40116；</li>
     *   <li>同一 openid 重复绑定幂等：已有绑定直接返回登录态，不产生重复行。</li>
     * </ol>
     */
    @Transactional
    public LoginResponse bindByTicket(String ticket, String email, String password) {
        // 1. 票据只读校验（不消费）
        if (!ticketService.isValid(ticket)) {
            throw new ApiException(ResultCode.WECHAT_TICKET_INVALID);
        }

        // 2. 凭证校验（失败抛 40111/40113，票据仍保留）
        LoginRequest req = new LoginRequest();
        req.setEmail(email);
        req.setPassword(password);
        LoginResponse login = authService.login(req);

        // 3. 消费票据（CAS 单次有效）
        String openid = ticketService.consume(ticket)
            .orElseThrow(() -> new ApiException(ResultCode.WECHAT_TICKET_INVALID));

        // 4. 幂等建绑
        if (bindRepository.findByOpenid(openid).isEmpty()) {
            User user = userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new ApiException(ResultCode.AUTH_EMAIL_NOT_FOUND));
            WechatBind bind = new WechatBind();
            bind.setOpenid(openid);
            bind.setUser(user);
            bindRepository.save(bind);
        }

        return login;
    }

    /**
     * 已绑定 openid 免密换 token（内部端点，wechat-service OAuth 回调调用）。
     * 未绑定 → empty（控制器返回 40115 业务码，走绑定引导流程）。
     */
    @Transactional
    public Optional<LoginResponse> loginByOpenid(String openid) {
        if (openid == null || openid.isBlank()) {
            return Optional.empty();
        }
        return bindRepository.findByOpenid(openid).map(bind -> {
            User user = bind.getUser();
            // 与密码登录同规则：有 token 复用（多会话），被 logout 清过则补发
            if (user.getAccessToken() == null || user.getAccessToken().isBlank()) {
                authService.ensureAccessToken(user);
            }
            return authService.buildLoginResponse(user);
        });
    }
}
