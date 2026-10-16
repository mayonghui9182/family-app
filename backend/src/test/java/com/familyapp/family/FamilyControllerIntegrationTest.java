package com.familyapp.family;
import org.springframework.test.annotation.DirtiesContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyapp.auth.UserContext;
import com.familyapp.common.ApiException;
import com.familyapp.family.dto.CreateFamilyRequest;
import com.familyapp.family.dto.TransferOwnerRequest;
import com.familyapp.family.dto.UpdateFamilyRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@Transactional
class FamilyControllerIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired FamilyService familyService;
    @Autowired FamilyMemberRepository familyMemberRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    Long defaultFamilyId;

    @BeforeEach
    void setUp() throws Exception {
        CreateFamilyRequest req = new CreateFamilyRequest();
        req.setName("默认家");
        String body = mvc.perform(post("/api/families")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn().getResponse().getContentAsString();
        defaultFamilyId = json.readTree(body).get("data").get("id").longValue();
    }

    @Test
    void F1_create_second_family_ok() throws Exception {
        CreateFamilyRequest req = new CreateFamilyRequest();
        req.setName("二家");
        mvc.perform(post("/api/families")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.name").value("二家"))
            .andExpect(jsonPath("$.data.id").isNumber());
    }

    @Test
    void F2_list_contains_two_families() throws Exception {
        CreateFamilyRequest req = new CreateFamilyRequest();
        req.setName("二家");
        mvc.perform(post("/api/families")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)));

        mvc.perform(get("/api/families"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(2)));
    }

    @Test
    void F3_get_by_id_ok() throws Exception {
        mvc.perform(get("/api/families/" + defaultFamilyId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.name").value("默认家"))
            .andExpect(jsonPath("$.data.id").value(defaultFamilyId.intValue()));
    }

    @Test
    void F4_get_not_found_40403() throws Exception {
        mvc.perform(get("/api/families/99999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(40403))
            .andExpect(jsonPath("$.msg", notNullValue()));
    }

    @Test
    void F5_update_name_ok() throws Exception {
        UpdateFamilyRequest req = new UpdateFamilyRequest();
        req.setName("改家名");
        mvc.perform(put("/api/families/" + defaultFamilyId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.name").value("改家名"));
    }

    @Test
    void F6_owner_leave_empty_family_physically_cleans_related_data() throws Exception {
        jdbc.update("INSERT INTO categories (name, color, sort_order, family_id, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                "待清理分类", "#409EFF", 0, defaultFamilyId, 1L);
        jdbc.update("INSERT INTO tasks (title, status, priority, source, family_id, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                "待清理任务", "PENDING", "MEDIUM", "MANUAL", defaultFamilyId, 1L);

        mvc.perform(post("/api/families/" + defaultFamilyId + "/leave"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM families WHERE id = ?", Integer.class, defaultFamilyId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM family_members WHERE family_id = ?", Integer.class, defaultFamilyId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE family_id = ?", Integer.class, defaultFamilyId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM categories WHERE family_id = ?", Integer.class, defaultFamilyId));
    }

    @Test
    void F7_transfer_owner_target_not_member_40052() throws Exception {
        TransferOwnerRequest req = new TransferOwnerRequest();
        req.setTargetUserId(9999L);
        mvc.perform(post("/api/families/" + defaultFamilyId + "/transfer-owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(40052))
            .andExpect(jsonPath("$.msg", notNullValue()));
    }

    @Test
    void S2_delete_last_family_returns_40054() throws Exception {
        mvc.perform(delete("/api/families/" + defaultFamilyId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(40054))
            .andExpect(jsonPath("$.msg", notNullValue()));
    }

    @Test
    void S3_S4_S5_delete_second_family_and_verify() throws Exception {
        CreateFamilyRequest req2 = new CreateFamilyRequest();
        req2.setName("二家");
        String body2 = mvc.perform(post("/api/families")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req2)))
            .andReturn().getResponse().getContentAsString();
        Long secondId = json.readTree(body2).get("data").get("id").longValue();

        mvc.perform(delete("/api/families/" + secondId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        mvc.perform(get("/api/families/" + secondId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(40403));

        mvc.perform(get("/api/families"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void S1_non_owner_delete_returns_40303() throws Exception {
        List<FamilyMember> members = familyMemberRepository
                .findAllByFamilyIdAndLeftAtIsNull(defaultFamilyId);
        FamilyMember ownerMember = members.get(0);
        ownerMember.setRole(FamilyRole.ADULT);
        familyMemberRepository.save(ownerMember);

        try {
            UserContext.setCurrent(new UserContext.CurrentUser(
                    ownerMember.getUserId(), "a@b.c", "u", defaultFamilyId, "ADULT"));
            ApiException ex = assertThrows(ApiException.class,
                    () -> familyService.deleteFamily(defaultFamilyId));
            assertEquals(40303, ex.getCode().getCode());
        } finally {
            UserContext.clear();
        }
    }
}
