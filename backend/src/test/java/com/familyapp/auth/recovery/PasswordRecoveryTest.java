package com.familyapp.auth.recovery;

import com.familyapp.user.User;
import com.familyapp.user.UserRepository;
import com.familyapp.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:passwordrecovery;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "familyapp.password-reset.trusted-proxies=192.0.2.10"})
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
class PasswordRecoveryTest {
    @Autowired PasswordResetTokens tokens;
    @Autowired PasswordResetRepository resets;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired MockMvc mvc;
    @MockBean RecoveryMail mail;
    User account() {
        User u = new User(); u.setEmail(UUID.randomUUID()+"@example.test"); u.setNickname("找回验收");
        u.setPasswordHash(encoder.encode("old-password")); u.setAccessToken(UUID.randomUUID().toString().replace("-", "").repeat(2));
        u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now()); u.setTokenUpdatedAt(Instant.now());
        return users.save(u);
    }
    @Test void resetChangesPasswordAndRevokesSessionOnce() throws Exception {
        var user=account(); var delivery=tokens.issue(user.getEmail());
        assertThat(resets.findById(user.getId()).orElseThrow().tokenHash).isNotEqualTo(delivery.token());
        mvc.perform(post("/api/auth/password-reset/confirm").contentType("application/json")
            .content("{\"token\":\""+delivery.token()+"\",\"password\":\"new-password\"}"))
            .andExpect(status().isOk());
        var updated=users.findById(user.getId()).orElseThrow();
        assertThat(encoder.matches("new-password",updated.getPasswordHash())).isTrue();
        assertThat(updated.getAccessToken()).isNull();
        mvc.perform(get("/api/auth/me").header("X-Access-Token",user.getAccessToken())).andExpect(status().isUnauthorized());
        assertThatThrownBy(()->tokens.consume(delivery.token(),"another-password")).isInstanceOf(ApiException.class);
    }
    @Test void replacementAndExpiryRejectOldLinks() {
        var user=account(); var old=tokens.issue(user.getEmail()); var current=tokens.issue(user.getEmail());
        assertThatThrownBy(()->tokens.consume(old.token(),"new-password")).isInstanceOf(ApiException.class);
        var row=resets.findById(user.getId()).orElseThrow(); row.expiresAt=Instant.now().minusSeconds(1); resets.save(row);
        assertThatThrownBy(()->tokens.consume(current.token(),"new-password")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->tokens.consume("invalid","new-password")).isInstanceOf(ApiException.class);
    }
    @Test void concurrentConsumptionSucceedsOnlyOnce() throws Exception {
        var user=account(); var token=tokens.issue(user.getEmail()).token();
        var executor=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        Callable<Boolean> consume=()->{start.await(); try{tokens.consume(token,"new-password");return true;}catch(ApiException e){return false;}};
        try {
            var a=executor.submit(consume);var b=executor.submit(consume); start.countDown();
            assertThat(java.util.List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        } finally {executor.shutdownNow();}
    }
    @Test void requestsAreUniformAndMailContainsOnlyKnownRecipient() throws Exception {
        when(mail.available()).thenReturn(true); var user=account();
        for(String email:java.util.List.of(user.getEmail(),"unknown-"+UUID.randomUUID()+"@example.test")) {
            mvc.perform(post("/api/auth/password-reset/request").contentType("application/json")
                .content("{\"email\":\""+email+"\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.code").value(0));
        }
        verify(mail,timeout(3000)).send(argThat(d->d.email().equals(user.getEmail())&&d.token().matches("[0-9a-f]{64}")));
    }
    @Test void unavailableAndAnonymousProtectedEndpoints() throws Exception {
        when(mail.available()).thenReturn(false);
        mvc.perform(post("/api/auth/password-reset/request").contentType("application/json").content("{\"email\":\"a@example.test\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50371));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/change-password").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
    }
    @Test void limiterRejectsExcessAttempts() {
        var limiter=new RecoveryLimiter();limiter.check("same",1);
        assertThatThrownBy(()->limiter.check("same",1)).isInstanceOf(ApiException.class);
        limiter.check("other",1);
    }
    @Test void proxyClientsHaveSeparateLimitsAndDirectCallersCannotRotateHeaders() throws Exception {
        String body = "{\"token\":\"invalid\",\"password\":\"new-password\"}";
        for (int i = 0; i < 30; i++) {
            mvc.perform(post("/api/auth/password-reset/confirm").with(r -> { r.setRemoteAddr("192.0.2.10"); return r; })
                    .header("X-Real-IP", "203.0.113.40").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/auth/password-reset/confirm").with(r -> { r.setRemoteAddr("192.0.2.10"); return r; })
                .header("X-Real-IP", "203.0.113.40").contentType("application/json").content(body))
                .andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/auth/password-reset/confirm").with(r -> { r.setRemoteAddr("192.0.2.10"); return r; })
                .header("X-Real-IP", "203.0.113.41").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        for (int i = 0; i < 31; i++) {
            mvc.perform(post("/api/auth/password-reset/confirm").with(r -> { r.setRemoteAddr("198.51.100.42"); return r; })
                    .header("X-Real-IP", "203.0.113." + i).contentType("application/json").content(body))
                    .andExpect(status().is(i == 30 ? 429 : 400));
        }
    }
}
