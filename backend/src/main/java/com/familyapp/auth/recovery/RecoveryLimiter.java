package com.familyapp.auth.recovery;

import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** 单实例有界滑动窗口；不使用客户端可伪造的转发头作为来源。 */
@Component
public class RecoveryLimiter {
    private final Map<String, ArrayDeque<Instant>> windows = new HashMap<>();
    public synchronized void check(String key, int limit) {
        Instant cutoff = Instant.now().minusSeconds(900);
        windows.values().forEach(q -> { while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) q.removeFirst(); });
        windows.values().removeIf(ArrayDeque::isEmpty);
        if (!windows.containsKey(key) && windows.size() >= 10000) throw limited();
        var queue = windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        if (queue.size() >= limit) throw limited();
        queue.addLast(Instant.now());
    }
    private ApiException limited() { return new ApiException(ResultCode.PASSWORD_RESET_RATE_LIMITED); }
}
