package com.familyapp.auth.recovery;

import com.familyapp.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/password-reset")
public class PasswordResetController {
    private final RecoveryRequests requests;
    private final PasswordResetTokens tokens;
    private final RecoveryLimiter limiter;
    private final RecoverySource source;
    public PasswordResetController(RecoveryRequests requests, PasswordResetTokens tokens, RecoveryLimiter limiter, RecoverySource source) {
        this.requests = requests; this.tokens = tokens; this.limiter = limiter; this.source = source;
    }
    public record Request(@NotBlank @Email @Size(max = 120) String email) {}
    public record Confirm(@NotBlank @Size(max = 64) String token,
                          @NotNull @Size(min = 8, max = 64, message = "密码需要8到64个字符") String password) {}
    @PostMapping("/request") @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<Void> request(@Valid @RequestBody Request body, HttpServletRequest request) {
        requests.request(body.email().trim(), source.resolve(request));
        return Result.ok();
    }
    @PostMapping("/confirm")
    public Result<Void> confirm(@Valid @RequestBody Confirm body, HttpServletRequest request) {
        limiter.check("confirm-ip:" + PasswordResetTokens.hash(source.resolve(request)), 30);
        tokens.consume(body.token(), body.password());
        return Result.ok();
    }
}
