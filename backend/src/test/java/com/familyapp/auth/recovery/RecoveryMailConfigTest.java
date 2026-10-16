package com.familyapp.auth.recovery;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

class RecoveryMailConfigTest {
    private MockEnvironment config(String origin) {
        return new MockEnvironment().withProperty("familyapp.password-reset.enabled", "true")
                .withProperty("familyapp.password-reset.public-url", origin)
                .withProperty("familyapp.password-reset.from", "recovery@example.test")
                .withProperty("familyapp.password-reset.smtp-host", "smtp.example.test");
    }
    @Test void requiresExplicitSecureOriginAndSender() {
        assertThat(new RecoveryMail(new MockEnvironment()).available()).isFalse();
        for (String origin : java.util.List.of("http://example.test", "https://user@example.test", "https://example.test/path", "https://example.test?next=elsewhere")) {
            assertThat(new RecoveryMail(config(origin)).available()).isFalse();
        }
        assertThat(new RecoveryMail(config("https://example.test")).available()).isTrue();
        assertThat(new RecoveryMail(config("https://example.test").withProperty("familyapp.password-reset.from", "bad\r\nSender: attacker@example.test")).available()).isFalse();
    }
    @Test void plainHttpExceptionIsLimitedToExplicitLocalTesting() {
        assertThat(new RecoveryMail(config("http://127.0.0.1:15173")).available()).isFalse();
        assertThat(new RecoveryMail(config("http://127.0.0.1:15173").withProperty("familyapp.password-reset.allow-local-http", "true")).available()).isTrue();
        assertThat(new RecoveryMail(config("http://example.test").withProperty("familyapp.password-reset.allow-local-http", "true")).available()).isFalse();
    }
}
