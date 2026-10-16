package com.familyapp.auth.recovery;

import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.util.Locale;
import java.util.concurrent.*;

/** 所有邮箱申请均进入相同队列，响应不等待账号查询或邮件发送。 */
@Service
public class RecoveryRequests {
    private final PasswordResetTokens tokens;
    private final RecoveryMail mail;
    private final RecoveryLimiter limiter;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "password-recovery"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    public RecoveryRequests(PasswordResetTokens tokens, RecoveryMail mail, RecoveryLimiter limiter) {
        this.tokens = tokens; this.mail = mail; this.limiter = limiter;
    }
    public void request(String email, String source) {
        if (!mail.available()) throw new ApiException(ResultCode.PASSWORD_RESET_UNAVAILABLE);
        limiter.check("request-ip:" + PasswordResetTokens.hash(source), 10);
        limiter.check("email:" + PasswordResetTokens.hash(email.toLowerCase(Locale.ROOT)), 3);
        try {
            executor.execute(() -> {
                try {
                    var delivery = tokens.issue(email);
                    if (delivery != null) mail.send(delivery);
                } catch (Exception ignored) {
                    org.slf4j.LoggerFactory.getLogger(RecoveryRequests.class).warn("密码找回后台处理失败，请检查发信服务；未记录账号或凭证");
                }
            });
        } catch (RejectedExecutionException e) { throw new ApiException(ResultCode.PASSWORD_RESET_UNAVAILABLE); }
    }
    @PreDestroy public void stop() { executor.shutdownNow(); }
}
