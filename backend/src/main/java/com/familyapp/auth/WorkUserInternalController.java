package com.familyapp.auth;

import com.familyapp.common.ApiException;
import com.familyapp.common.Result;
import com.familyapp.common.ResultCode;
import com.familyapp.user.UserRepository;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 工作服务机器调用：由内部密钥拦截器保护，仅返回有效账号标识。 */
@RestController
@RequestMapping("/api/internal/work-users")
public class WorkUserInternalController {
    private final UserRepository users;

    public WorkUserInternalController(UserRepository users) { this.users = users; }

    @GetMapping("/{id}")
    public Result<Map<String, Long>> active(@PathVariable Long id) {
        var user = users.findById(id).filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(ResultCode.AUTH_TOKEN_INVALID));
        return Result.ok(Map.of("userId", user.getId()));
    }
}
