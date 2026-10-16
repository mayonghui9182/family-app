package com.familyapp.family;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyapp.family.dto.AcceptInviteRequest;
import com.familyapp.family.dto.CreateFamilyRequest;
import com.familyapp.family.dto.InviteCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InviteControllerIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    Long familyId;
    Long invitedUserId;

    @BeforeEach
    void setUp() throws Exception {
        invitedUserId = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM users", Long.class);
        jdbc.update("INSERT INTO users (id, email, nickname, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                invitedUserId, "u" + invitedUserId + "@t.com", "u2", "test-password-hash");
        CreateFamilyRequest req = new CreateFamilyRequest();
        req.setName("默认家");
        String body = mvc.perform(post("/api/families")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn().getResponse().getContentAsString();
        familyId = json.readTree(body).get("data").get("id").longValue();
    }

    @Test
    void I1_create_invite_ok() throws Exception {
        InviteCreateRequest req = new InviteCreateRequest();
        req.setMaxUses(10);
        req.setTtlDays(7);
        mvc.perform(post("/api/families/" + familyId + "/invites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.code", notNullValue()))
            .andExpect(jsonPath("$.data.id").isNumber());
    }

    @Test
    void I2_list_invites_ok() throws Exception {
        InviteCreateRequest req = new InviteCreateRequest();
        req.setMaxUses(5);
        req.setTtlDays(3);
        mvc.perform(post("/api/families/" + familyId + "/invites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)));

        mvc.perform(get("/api/families/" + familyId + "/invites"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(1)));
    }

    @Test
    void I3_cancel_invite_ok() throws Exception {
        InviteCreateRequest req = new InviteCreateRequest();
        req.setMaxUses(5);
        String createBody = mvc.perform(post("/api/families/" + familyId + "/invites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn().getResponse().getContentAsString();
        Long inviteId = json.readTree(createBody).get("data").get("id").longValue();

        mvc.perform(delete("/api/families/" + familyId + "/invites/" + inviteId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void I4_accept_cancelled_invite_returns_error() throws Exception {
        InviteCreateRequest req = new InviteCreateRequest();
        req.setMaxUses(5);
        String createBody = mvc.perform(post("/api/families/" + familyId + "/invites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn().getResponse().getContentAsString();
        Long inviteId = json.readTree(createBody).get("data").get("id").longValue();
        String inviteCode = json.readTree(createBody).get("data").get("code").textValue();

        mvc.perform(delete("/api/families/" + familyId + "/invites/" + inviteId))
            .andExpect(status().isOk());

        AcceptInviteRequest acceptReq = new AcceptInviteRequest();
        acceptReq.setCode(inviteCode);
        mvc.perform(post("/api/invites/accept")
                .header("X-Test-User-Id", invitedUserId.toString())
                .header("X-Test-Email", "u2@t.com")
                .header("X-Test-Nick", "u2")
                .header("X-Test-Family-Id", familyId.toString())
                .header("X-Test-Role", "ADULT")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(acceptReq)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code", not(0)))
            .andExpect(jsonPath("$.msg", notNullValue()));
    }

    @Test
    void I5_accept_invite_success() throws Exception {
        InviteCreateRequest req = new InviteCreateRequest();
        req.setMaxUses(5);
        req.setTtlDays(7);
        String createBody = mvc.perform(post("/api/families/" + familyId + "/invites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn().getResponse().getContentAsString();
        String inviteCode = json.readTree(createBody).get("data").get("code").textValue();

        AcceptInviteRequest acceptReq = new AcceptInviteRequest();
        acceptReq.setCode(inviteCode);
        mvc.perform(post("/api/invites/accept")
                .header("X-Test-User-Id", invitedUserId.toString())
                .header("X-Test-Email", "u2@t.com")
                .header("X-Test-Nick", "u2")
                .header("X-Test-Family-Id", familyId.toString())
                .header("X-Test-Role", "ADULT")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(acceptReq)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM person_profiles WHERE family_id = ? AND user_id = ? AND deleted_at IS NULL",
                Integer.class, familyId, invitedUserId));
    }

    @Test
    void I6_invited_user_sees_family_in_list() throws Exception {
        InviteCreateRequest req = new InviteCreateRequest();
        req.setMaxUses(5);
        req.setTtlDays(7);
        String createBody = mvc.perform(post("/api/families/" + familyId + "/invites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn().getResponse().getContentAsString();
        String inviteCode = json.readTree(createBody).get("data").get("code").textValue();

        AcceptInviteRequest acceptReq = new AcceptInviteRequest();
        acceptReq.setCode(inviteCode);
        mvc.perform(post("/api/invites/accept")
                .header("X-Test-User-Id", invitedUserId.toString())
                .header("X-Test-Email", "u2@t.com")
                .header("X-Test-Nick", "u2")
                .header("X-Test-Family-Id", familyId.toString())
                .header("X-Test-Role", "ADULT")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(acceptReq)))
            .andExpect(status().isOk());

        mvc.perform(get("/api/families")
                .header("X-Test-User-Id", invitedUserId.toString())
                .header("X-Test-Email", "u2@t.com")
                .header("X-Test-Nick", "u2")
                .header("X-Test-Family-Id", familyId.toString())
                .header("X-Test-Role", "ADULT"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data[*].id", hasItem(familyId.intValue())));
    }
}
