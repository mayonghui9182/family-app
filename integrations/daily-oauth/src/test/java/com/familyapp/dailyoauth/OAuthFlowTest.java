package com.familyapp.dailyoauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实框架、独立 H2 与虚构账号验证浏览器授权全流程。 */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:oauthflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=",
    "daily.issuer=https://app.example.test/daily-oauth","daily.resource=https://app.example.test/mcp/daily",
    "daily.client-id=test-chatgpt","daily.client-secret=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "daily.redirect-uri=https://client.example.test/callback",
    "daily.reader-id=test-reader","daily.reader-secret=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"})
@AutoConfigureMockMvc
class OAuthFlowTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    String verifier="01234567890123456789012345678901234567890123456789";

    @BeforeEach void account() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS public.users(id BIGINT PRIMARY KEY,email VARCHAR(100),password_hash VARCHAR(100),deleted_at TIMESTAMP)");
        jdbc.update("DELETE FROM oauth2_authorization");jdbc.update("DELETE FROM oauth2_authorization_consent");
        jdbc.update("DELETE FROM public.users");
        jdbc.update("INSERT INTO public.users VALUES(1,?,?,NULL)","mock@example.test",encoder.encode("password123"));
    }
    MockHttpSession login() throws Exception {
        return login("mock@example.test");
    }
    MockHttpSession login(String email) throws Exception {
        var result=mvc.perform(post("/login").servletPath("/login").with(csrf()).param("username",email).param("password","password123"))
            .andExpect(status().is3xxRedirection()).andReturn();
        return (MockHttpSession)result.getRequest().getSession(false);
    }
    String code(MockHttpSession session) throws Exception {
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
            .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var request=mvc.perform(get("/oauth2/authorize").servletPath("/oauth2/authorize").session(session)
            .queryParam("response_type","code").queryParam("client_id","test-chatgpt").queryParam("redirect_uri","https://client.example.test/callback")
            .queryParam("scope","daily:read").queryParam("state",UUID.randomUUID().toString())
            .queryParam("resource","https://app.example.test/mcp/daily").queryParam("code_challenge",challenge).queryParam("code_challenge_method","S256"))
            .andExpect(status().is3xxRedirection()).andReturn();
        String location=request.getResponse().getRedirectedUrl();
        var params=UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        if(params.getFirst("code")!=null)return params.getFirst("code");
        String consentState=params.getFirst("state");assertNotNull(consentState,location);
        consentState=java.net.URLDecoder.decode(consentState,StandardCharsets.UTF_8);
        var consent=mvc.perform(post("/oauth2/authorize").servletPath("/oauth2/authorize").session(session).with(csrf())
            .param("client_id","test-chatgpt").param("state",consentState).param("scope","daily:read"))
            .andExpect(status().is3xxRedirection()).andReturn();
        String callback=consent.getResponse().getRedirectedUrl();
        String code=UriComponentsBuilder.fromUriString(callback).build().getQueryParams().getFirst("code");
        assertNotNull(code,callback);return code;
    }

    String exchange(String code,String proof,int status) throws Exception {
        return mvc.perform(post("/oauth2/token").servletPath("/oauth2/token").with(httpBasic("test-chatgpt","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
            .param("grant_type","authorization_code").param("code",code).param("code_verifier",proof)
            .param("redirect_uri","https://client.example.test/callback").param("resource","https://app.example.test/mcp/daily"))
            .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
    }
    String introspect(String token) throws Exception {
        return mvc.perform(post("/oauth2/introspect").with(httpBasic("test-reader","bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
            .param("token",token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test void consent_pkce_exchange_introspection_reuse_and_revocation() throws Exception {
        var session=login();String authorizationCode=code(session);
        String token=json.readTree(exchange(authorizationCode,verifier,200)).path("access_token").asText();
        assertFalse(token.isEmpty());var claims=json.readTree(introspect(token));
        assertTrue(claims.path("active").asBoolean());assertEquals("1",claims.path("sub").asText());
        assertEquals("https://app.example.test/daily-oauth",claims.path("iss").asText());
        assertTrue(claims.path("aud").toString().contains("https://app.example.test/mcp/daily"));
        exchange(authorizationCode,verifier,400);
        mvc.perform(post("/oauth2/revoke").with(httpBasic("test-chatgpt","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
            .param("token",token)).andExpect(status().isOk());
        assertFalse(json.readTree(introspect(token)).path("active").asBoolean());
    }
    @Test void csrf_and_wrong_pkce_are_rejected() throws Exception {
        mvc.perform(post("/login").param("username","mock@example.test").param("password","password123"))
            .andExpect(status().isForbidden());
        exchange(code(login()),"wrong-proof",400);
    }
    @Test void resource_and_plain_pkce_are_rejected_before_authorization() throws Exception {
        mvc.perform(get("/oauth2/authorize").servletPath("/oauth2/authorize").param("resource","https://other.example.test"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/oauth2/authorize").servletPath("/oauth2/authorize").param("resource","https://app.example.test/mcp/daily")
            .param("code_challenge_method","plain")).andExpect(status().isBadRequest());
    }

    @Test void persisted_tokens_refresh_rotate_and_expire() throws Exception {
        var response=json.readTree(exchange(code(login()),verifier,200));
        String refresh=response.path("refresh_token").asText();assertFalse(refresh.isEmpty());
        String access=response.path("access_token").asText();
        var repository=new org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository(jdbc);
        var restored=new org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService(jdbc,repository);
        assertNotNull(restored.findByToken(access,org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN));
        String refreshed=mvc.perform(post("/oauth2/token").servletPath("/oauth2/token")
            .with(httpBasic("test-chatgpt","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).param("grant_type","refresh_token")
            .param("refresh_token",refresh).param("resource","https://app.example.test/mcp/daily"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String next=json.readTree(refreshed).path("access_token").asText();
        assertTrue(json.readTree(introspect(next)).path("active").asBoolean());
        assertNotEquals(refresh,json.readTree(refreshed).path("refresh_token").asText());
        mvc.perform(post("/oauth2/token").servletPath("/oauth2/token")
            .with(httpBasic("test-chatgpt","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).param("grant_type","refresh_token")
            .param("refresh_token",refresh).param("resource","https://app.example.test/mcp/daily"))
            .andExpect(status().isBadRequest());
        jdbc.update("UPDATE oauth2_authorization SET access_token_issued_at=?,access_token_expires_at=? WHERE access_token_value=?",
            java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)),
            java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),next);
        assertFalse(json.readTree(introspect(next)).path("active").asBoolean());
    }

    @Test void personal_revocation_is_scoped_and_protected_by_csrf() throws Exception {
        var first=login();String access=json.readTree(exchange(code(first),verifier,200)).path("access_token").asText();
        String another=json.readTree(exchange(code(first),verifier,200)).path("access_token").asText();
        String id=jdbc.queryForObject("SELECT id FROM oauth2_authorization WHERE access_token_value=?",String.class,access);
        jdbc.update("INSERT INTO public.users SELECT 2,'second@example.test',password_hash,NULL FROM public.users WHERE id=1");
        var second=login("second@example.test");
        mvc.perform(post("/authorizations/revoke").session(first).param("id",id)).andExpect(status().isForbidden());
        mvc.perform(post("/authorizations/revoke").session(second).with(csrf()).param("id",id)).andExpect(status().is3xxRedirection());
        assertTrue(json.readTree(introspect(access)).path("active").asBoolean());
        mvc.perform(post("/authorizations/revoke").session(first).with(csrf()).param("id",id)).andExpect(status().is3xxRedirection());
        assertFalse(json.readTree(introspect(access)).path("active").asBoolean());
        assertFalse(json.readTree(introspect(another)).path("active").asBoolean());
    }

    @Test void deleted_account_cannot_exchange_a_previously_issued_code() throws Exception {
        String authorizationCode=code(login());
        jdbc.update("UPDATE public.users SET deleted_at=CURRENT_TIMESTAMP WHERE id=1");
        exchange(authorizationCode,verifier,400);
    }

    @Test void wrong_redirect_and_declined_consent_do_not_grant_access() throws Exception {
        var session=login();
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
            .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        mvc.perform(get("/oauth2/authorize").servletPath("/oauth2/authorize").session(session)
            .queryParam("response_type","code").queryParam("client_id","test-chatgpt")
            .queryParam("redirect_uri","https://unregistered.example.test/callback")
            .queryParam("scope","daily:read").queryParam("resource","https://app.example.test/mcp/daily")
            .queryParam("code_challenge",challenge).queryParam("code_challenge_method","S256"))
            .andExpect(status().isBadRequest());
        var result=mvc.perform(get("/oauth2/authorize").servletPath("/oauth2/authorize").session(session)
            .queryParam("response_type","code").queryParam("client_id","test-chatgpt")
            .queryParam("redirect_uri","https://client.example.test/callback").queryParam("state","decline-test")
            .queryParam("scope","daily:read").queryParam("resource","https://app.example.test/mcp/daily")
            .queryParam("code_challenge",challenge).queryParam("code_challenge_method","S256"))
            .andExpect(status().is3xxRedirection()).andReturn();
        String state=java.net.URLDecoder.decode(UriComponentsBuilder.fromUriString(result.getResponse().getRedirectedUrl())
            .build().getQueryParams().getFirst("state"),StandardCharsets.UTF_8);
        var denied=mvc.perform(post("/oauth2/authorize").servletPath("/oauth2/authorize").session(session).with(csrf())
            .param("client_id","test-chatgpt").param("state",state)).andExpect(status().is3xxRedirection()).andReturn();
        assertTrue(denied.getResponse().getRedirectedUrl().contains("error=access_denied"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM oauth2_authorization WHERE access_token_value IS NOT NULL",Integer.class));
    }

    @Test void valid_authorization_parameters_with_wrong_resource_are_rejected() throws Exception {
        mvc.perform(get("/oauth2/authorize").servletPath("/oauth2/authorize").session(login())
            .queryParam("response_type","code").queryParam("client_id","test-chatgpt")
            .queryParam("redirect_uri","https://client.example.test/callback").queryParam("scope","daily:read")
            .queryParam("resource","https://wrong.example.test/mcp").queryParam("code_challenge_method","S256")
            .queryParam("code_challenge","dummy")).andExpect(status().isBadRequest())
            .andExpect(content().json("{\"error\":\"invalid_target\"}"));
    }

    @Test void concurrent_code_exchange_has_one_winner() throws Exception {
        String authorizationCode=code(login());
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> attempt=()->{
                start.await();
                return mvc.perform(post("/oauth2/token").servletPath("/oauth2/token")
                    .with(httpBasic("test-chatgpt","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                    .param("grant_type","authorization_code").param("code",authorizationCode).param("code_verifier",verifier)
                    .param("redirect_uri","https://client.example.test/callback").param("resource","https://app.example.test/mcp/daily"))
                    .andReturn().getResponse().getStatus();
            };
            var first=workers.submit(attempt);var second=workers.submit(attempt);start.countDown();
            var statuses=new java.util.ArrayList<>(java.util.List.of(first.get(),second.get()));
            java.util.Collections.sort(statuses);assertEquals(java.util.List.of(200,400),statuses);
        } finally {workers.shutdownNow();}
    }

    @Test void repeated_login_attempts_are_limited() throws Exception {
        for(int count=0;count<11;count++)mvc.perform(post("/login").servletPath("/login").with(csrf())
            .param("username","throttle@example.test").param("password","wrong-password"))
            .andExpect(count<10?status().is3xxRedirection():status().isTooManyRequests());
    }

    @Test void secret_post_client_can_exchange_valid_pkce_code() throws Exception {
        mvc.perform(post("/oauth2/token").servletPath("/oauth2/token").param("client_id","test-chatgpt")
            .param("client_secret","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").param("grant_type","authorization_code")
            .param("code",code(login())).param("code_verifier",verifier)
            .param("redirect_uri","https://client.example.test/callback").param("resource","https://app.example.test/mcp/daily"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.token_type").value("Bearer"));
    }
}
