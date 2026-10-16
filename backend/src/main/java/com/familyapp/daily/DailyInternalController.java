package com.familyapp.daily;

import com.familyapp.common.ApiException;
import com.familyapp.common.Result;
import com.familyapp.common.ResultCode;
import com.familyapp.user.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

/** OAuth 资源服务专用只读入口；机器密钥由现有内部拦截器验证，默认不加载。 */
@RestController
@RequestMapping("/api/internal/daily-read")
@ConditionalOnProperty(name="daily.remote-enabled",havingValue="true")
public class DailyInternalController {
    private final DailyService daily;
    private final UserRepository users;

    public DailyInternalController(DailyService daily,UserRepository users,
            @org.springframework.beans.factory.annotation.Value("${familyapp.auth.enabled:true}") boolean authEnabled) {
        if(!authEnabled)throw new IllegalStateException("远程每日读取禁止关闭内部认证");
        this.daily=daily;this.users=users;
    }

    @GetMapping("/{userId}/{kind}/{date}")
    public Result<?> read(@PathVariable Long userId,@PathVariable String kind,@PathVariable LocalDate date) {
        if(!java.util.Set.of("plans","reviews").contains(kind))
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION);
        if(userId<=0 || users.findById(userId).filter(u->u.getDeletedAt()==null).isEmpty())
            throw new ApiException(ResultCode.AUTH_TOKEN_INVALID);
        return Result.ok(daily.read(userId,date,kind.equals("plans")?"PLAN":"REVIEW"));
    }
}
