package com.familyapp.wechat.conversation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class WechatConversationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WechatConversationConfiguration.class);

    @Test
    void defaultsKeepConversationAndWorkerDisabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            WechatConversationProperties properties =
                    context.getBean(WechatConversationProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.isWorkerEnabled()).isFalse();
            assertThat(properties.getLeaseSeconds()).isEqualTo(120);
            assertThat(properties.getTtlHours()).isEqualTo(24);
            assertThat(properties.getMaxAttempts()).isEqualTo(5);
            assertThat(properties.getRetryInitialDelayMs()).isEqualTo(1000);
            assertThat(properties.getRetryMaxDelayMs()).isEqualTo(60000);
        });
    }

    @Test
    void rejectsLegacyAndConversationModesTogether() {
        contextRunner
                .withPropertyValues(
                        "wechat.intake.enabled=true",
                        "wechat.conversation.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "WECHAT_INTAKE_ENABLED 与 WECHAT_CONVERSATION_ENABLED 不能同时开启");
                });
    }

    @Test
    void allowsEitherModeOnItsOwn() {
        contextRunner
                .withPropertyValues(
                        "wechat.intake.enabled=true",
                        "wechat.conversation.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());

        contextRunner
                .withPropertyValues(
                        "wechat.intake.enabled=false",
                        "wechat.conversation.enabled=true",
                        "wechat.conversation.worker-enabled=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsInvalidLeaseAndRetryRange() {
        contextRunner
                .withPropertyValues(
                        "wechat.conversation.lease-seconds=0",
                        "wechat.conversation.retry-initial-delay-ms=5000",
                        "wechat.conversation.retry-max-delay-ms=1000")
                .run(context -> assertThat(context).hasFailed());
    }
}
