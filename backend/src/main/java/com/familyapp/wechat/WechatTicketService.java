package com.familyapp.wechat;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信绑定一次性中间票据服务（Change 11，design.md D3）。
 *
 * <p>进程内存 Map 保存 ticket → openid：
 * <ul>
 *   <li>TTL 10 分钟，过期即失效；</li>
 *   <li>单次有效：CAS 保证并发下仅一次消费成功；</li>
 *   <li>openid 不进票据本身、不进 URL 以外的任何前端可见位置——票据是不透明随机串。</li>
 * </ul>
 * 进程重启票据全部失效（可接受：用户从公众号菜单重进即可）。
 */
@Service
public class WechatTicketService {

    /** 票据有效期：≤10 分钟（design.md D3 安全要求）。 */
    static final Duration TTL = Duration.ofMinutes(10);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    private record Ticket(String openid, Instant expiresAt, AtomicBoolean consumed) {}

    /** 为指定 openid 签发一次性票据，返回 32 位 hex 不透明串。 */
    public String mint(String openid) {
        String ticket = randomHex();
        tickets.put(ticket, new Ticket(openid, Instant.now().plus(TTL), new AtomicBoolean(false)));
        return ticket;
    }

    /**
     * 消费票据：有效且未消费过 → 返回 openid 并移除；
     * 不存在/已过期/已消费 → empty（调用方返回 WECHAT_TICKET_INVALID）。
     */
    public Optional<String> consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        Ticket t = tickets.get(ticket);
        if (t == null) {
            return Optional.empty();
        }
        if (t.expiresAt().isBefore(Instant.now())) {
            tickets.remove(ticket, t);
            return Optional.empty();
        }
        if (!t.consumed().compareAndSet(false, true)) {
            return Optional.empty();
        }
        tickets.remove(ticket, t);
        return Optional.of(t.openid());
    }

    /** 只读校验票据是否仍可消费（不消耗），凭证校验失败时票据须保留可重试。 */
    public boolean isValid(String ticket) {
        Ticket t = ticket == null ? null : tickets.get(ticket);
        return t != null
            && !t.consumed().get()
            && !t.expiresAt().isBefore(Instant.now());
    }

    private static String randomHex() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        char[] out = new char[32];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }
}
