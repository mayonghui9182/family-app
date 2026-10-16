package com.familyapp.daily;

import com.familyapp.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.*;

/** 默认不加载；禁止在内部认证关闭的配置中启用远程读取。 */
class DailyInternalConfigurationTest {
    private ApplicationContextRunner context(){return new ApplicationContextRunner()
        .withUserConfiguration(DailyInternalController.class)
        .withBean(DailyService.class,()->mock(DailyService.class))
        .withBean(UserRepository.class,()->mock(UserRepository.class));}

    @Test void default_off_and_auth_disabled_fail_closed() {
        context().run(ctx->assertEquals(0,ctx.getBeansOfType(DailyInternalController.class).size()));
        context().withPropertyValues("daily.remote-enabled=true","familyapp.auth.enabled=false")
            .run(ctx->{
                Throwable cause=ctx.getStartupFailure();assertNotNull(cause);boolean guarded=false;
                while(cause!=null) {
                    if(cause instanceof IllegalStateException && "远程每日读取禁止关闭内部认证".equals(cause.getMessage()))guarded=true;
                    cause=cause.getCause();
                }
                assertTrue(guarded,"必须由认证保护条件拒绝启动，而不是其他配置错误");
            });
    }
}
