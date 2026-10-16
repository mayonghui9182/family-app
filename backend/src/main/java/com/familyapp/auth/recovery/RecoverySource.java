package com.familyapp.auth.recovery;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;

/** 只信任明确列出的直接代理地址，不解析任意转发链，也不做主机名查询。 */
@Component
public class RecoverySource {
    private final Set<String> trusted = new HashSet<>();

    public RecoverySource(@Value("${familyapp.password-reset.trusted-proxies:}") String proxies,
                          @Value("${server.forward-headers-strategy:none}") String strategy) {
        if (!"none".equalsIgnoreCase(strategy)) {
            throw new IllegalArgumentException("密码找回来源校验要求禁用全局转发头解析，请设置 server.forward-headers-strategy=none");
        }
        if (proxies.isBlank()) return;
        for (String entry : proxies.split(",", -1)) {
            String address = literal(entry.trim());
            if (address == null) throw new IllegalArgumentException("密码找回可信代理必须是逗号分隔的完整 IP 地址");
            trusted.add(address);
        }
    }

    public String resolve(HttpServletRequest request) {
        String peer = literal(request.getRemoteAddr());
        if (peer == null) return "unknown-peer";
        if (!trusted.contains(peer)) return peer;
        var headers = request.getHeaders("X-Real-IP");
        if (headers == null || !headers.hasMoreElements()) return peer;
        String client = literal(headers.nextElement());
        // 重复、多值、带端口或无效地址一律回退，不能被当成新的限流键。
        if (headers.hasMoreElements() || client == null) return peer;
        return client;
    }

    private static String literal(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.contains(":")) {
            if (!value.matches("[0-9a-fA-F:.]+")) return null;
        } else {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) return null;
            for (String part : parts) {
                if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) return null;
            }
        }
        try { return InetAddress.getByName(value).getHostAddress(); }
        catch (java.net.UnknownHostException e) { return null; }
    }
}
