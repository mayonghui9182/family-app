package com.familyapp.family;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyapp.family.dto.AcceptInviteRequest;
import com.familyapp.family.dto.ChangeRoleRequest;
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
class MemberRoleIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired com.familyapp.user.UserRepository users;

    Long familyId;
    Long userId2 = 2L;

    @BeforeEach
    void setUp() throws Exception {
        // 邀请加入会创建本人档案，测试也必须提供真实存在的账号。
        com.familyapp.user.User user = new com.familyapp.user.User();
        user.setNickname("受邀成员");
        user.setEmail(java.util.UUID.randomUUID() + "@example.test");
        user.setPasswordHash("测试占位哈希");
        user.setCreatedAt(java.time.Instant.now());
        user.setUpdatedAt(java.time.Instant.now());
        userId2 = users.save(user).getId();
        CreateFamilyRequest cf = new CreateFamilyRequest();
        cf.setName("默认家");
        String famBody = mvc.perform(post("/api/families")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(cf)))
            .andReturn().getResponse().getContentAsString();
        familyId = json.readTree(famBody).get("data").get("id").longValue();

        InviteCreateRequest ir = new InviteCreateRequest();
        ir.setMaxUses(5);
        ir.setTtlDays(7);
        String invBody = mvc.perform(post("/api/families/" + familyId + "/invites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(ir)))
            .andReturn().getResponse().getContentAsString();
        String inviteCode = json.readTree(invBody).get("data").get("code").textValue();

        AcceptInviteRequest acceptReq = new AcceptInviteRequest();
        acceptReq.setCode(inviteCode);
        mvc.perform(post("/api/invites/accept")
                .header("X-Test-User-Id", userId2.toString())
                .header("X-Test-Email", "u2@t.com")
                .header("X-Test-Nick", "u2")
                .header("X-Test-Family-Id", familyId.toString())
                .header("X-Test-Role", "ADULT")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(acceptReq)))
            .andExpect(status().isOk());
    }

    @Test
    void R1_change_role_to_child_ok() throws Exception {
        ChangeRoleRequest req = new ChangeRoleRequest();
        req.setRole("CHILD");
        mvc.perform(post("/api/families/" + familyId + "/members/" + userId2)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void K1_kick_member_ok() throws Exception {
        mvc.perform(delete("/api/families/" + familyId + "/members/" + userId2))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void K2_after_kick_member_not_in_list() throws Exception {
        mvc.perform(delete("/api/families/" + familyId + "/members/" + userId2))
            .andExpect(status().isOk());

        mvc.perform(get("/api/families/" + familyId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.members[*].userId", not(hasItem(userId2.intValue()))));
    }
}
