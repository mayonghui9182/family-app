package com.familyapp.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyapp.auth.dto.LoginRequest;
import com.familyapp.auth.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
class AuthControllerIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void empty_invite_code_allows_create_or_defer_but_short_code_is_rejected() throws Exception {
        for (boolean create : new boolean[]{true, false}) {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"empty-invite-" + create + "@test.com\",\"nickname\":\"注册验收\","
                            + "\"password\":\"password123\",\"createFamily\":" + create + ",\"inviteCode\":\"\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accessToken", notNullValue()));
        }
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"short-invite@test.com\",\"nickname\":\"注册验收\","
                        + "\"password\":\"password123\",\"createFamily\":false,\"inviteCode\":\"ABC\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(40099));
    }

    @Test
    void R1_register_ok_returns_token_and_ids() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("r1@test.com");
        req.setNickname("R1用户");
        req.setPassword("password123");
        req.setFamilyName("R1家");

        var result = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.accessToken", notNullValue()))
            .andExpect(jsonPath("$.data.userId", notNullValue()))
            .andExpect(jsonPath("$.data.familyId", notNullValue()))
            .andExpect(jsonPath("$.data.nickname").value("R1用户"))
            .andExpect(jsonPath("$.data.email").value("r1@test.com"))
            .andReturn();
        var data = json.readTree(result.getResponse().getContentAsString()).get("data");
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM person_profiles WHERE family_id = ? AND user_id = ? AND name = ? AND deleted_at IS NULL",
                Integer.class, data.get("familyId").asLong(), data.get("userId").asLong(), "R1用户"));
    }

    @Test
    void R2_register_duplicate_email_returns_40901() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("r2@test.com");
        req.setNickname("R2用户");
        req.setPassword("password123");
        mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isCreated());

        mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(40901))
            .andExpect(jsonPath("$.msg", notNullValue()));
    }

    @Test
    void R4_register_can_defer_family_selection() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("r4@test.com");
        req.setNickname("稍后选择用户");
        req.setPassword("password123");
        req.setCreateFamily(false);

        var result = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.accessToken", notNullValue()))
            .andExpect(jsonPath("$.data.familyId").doesNotExist())
            .andReturn();
        long userId = json.readTree(result.getResponse().getContentAsString()).get("data").get("userId").asLong();
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM family_members WHERE user_id = ? AND left_at IS NULL", Integer.class, userId));
    }

    @Test
    void R3_register_password_too_short_returns_40099() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("r3@test.com");
        req.setNickname("R3用户");
        req.setPassword("abc123");

        mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(40099))
            .andExpect(jsonPath("$.msg", notNullValue()));
    }

    @Test
    void L1_login_ok_returns_token() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("l1@test.com");
        reg.setNickname("L1用户");
        reg.setPassword("loginpass123");
        mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(reg)))
            .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest();
        login.setEmail("l1@test.com");
        login.setPassword("loginpass123");
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(login)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.accessToken", notNullValue()))
            .andExpect(jsonPath("$.data.nickname").value("L1用户"))
            .andExpect(jsonPath("$.data.families").isArray());
    }

    @Test
    void L2_login_wrong_password_returns_401xx() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("l2@test.com");
        reg.setNickname("L2用户");
        reg.setPassword("rightpass123");
        mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(reg)))
            .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest();
        login.setEmail("l2@test.com");
        login.setPassword("wrongpassword");
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(login)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(40113))
            .andExpect(jsonPath("$.msg", notNullValue()));
    }

    @Test
    void L3_login_nonexistent_user_returns_401xx() throws Exception {
        LoginRequest login = new LoginRequest();
        login.setEmail("nobody@test.com");
        login.setPassword("whatever123");
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(login)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(40111))
            .andExpect(jsonPath("$.msg", notNullValue()));
    }

    @Test
    void M1_me_with_token_ok() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("m1@test.com");
        reg.setNickname("M1用户");
        reg.setPassword("mypass123");
        String createResp = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(reg)))
            .andReturn().getResponse().getContentAsString();
        String token = json.readTree(createResp).get("data").get("accessToken").textValue();

        mvc.perform(get("/api/auth/me").header("X-Access-Token", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.nickname").value("M1用户"))
            .andExpect(jsonPath("$.data.accessToken", notNullValue()));
    }

    @Test
    void M2_me_no_token_returns_40100() throws Exception {
        mvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(40100))
            .andExpect(jsonPath("$.msg", notNullValue()));
    }

    @Test
    void M3_me_invalid_token_returns_401xx() throws Exception {
        mvc.perform(get("/api/auth/me").header("X-Access-Token", "invalid-token-12345"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code", not(0)))
            .andExpect(jsonPath("$.msg", notNullValue()));
    }

    @Test
    void L4_logout_ok() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("l4@test.com");
        reg.setNickname("L4用户");
        reg.setPassword("mypass123");
        String createResp = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(reg)))
            .andReturn().getResponse().getContentAsString();
        String token = json.readTree(createResp).get("data").get("accessToken").textValue();

        mvc.perform(post("/api/auth/logout").header("X-Access-Token", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void L5_me_after_logout_returns_401xx() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("l5@test.com");
        reg.setNickname("L5用户");
        reg.setPassword("mypass123");
        String createResp = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(reg)))
            .andReturn().getResponse().getContentAsString();
        String token = json.readTree(createResp).get("data").get("accessToken").textValue();

        mvc.perform(post("/api/auth/logout").header("X-Access-Token", token))
            .andExpect(status().isOk());

        mvc.perform(get("/api/auth/me").header("X-Access-Token", token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code", not(0)))
            .andExpect(jsonPath("$.msg", notNullValue()));
    }
}
