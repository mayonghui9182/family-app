package com.familyapp.auth;

import com.familyapp.auth.dto.AuthResponse;
import com.familyapp.auth.dto.ChangePasswordRequest;
import com.familyapp.auth.dto.LoginRequest;
import com.familyapp.auth.dto.LoginResponse;
import com.familyapp.auth.dto.RegisterRequest;
import com.familyapp.common.Result;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 认证 Controller：注册、登录、登出、获取当前用户信息、修改密码。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return Result.ok(authService.register(req));
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return Result.ok(authService.login(req));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<LoginResponse> me() {
        return Result.ok(authService.me());
    }

    /** 修改密码：旧密码二次确认 + 新密码长度校验 + 不能与旧密码一致。 */
    @PostMapping("/change-password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        authService.changePassword(req);
        return Result.ok();
    }
}
