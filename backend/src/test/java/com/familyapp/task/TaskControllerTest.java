package com.familyapp.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyapp.family.FamilyMember;
import com.familyapp.family.FamilyMemberRepository;
import com.familyapp.family.FamilyRepository;
import com.familyapp.task.dto.CreateTaskRequest;
import com.familyapp.task.dto.TaskResponse;
import com.familyapp.task.dto.UpdateTaskRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.Instant;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TaskRepository taskRepository;
    @Autowired FamilyRepository familyRepository;
    @Autowired FamilyMemberRepository familyMemberRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        taskRepository.deleteAll();
        familyMemberRepository.deleteAll();
        familyRepository.deleteAll();
        Instant now = Instant.now();
        jdbcTemplate.update("INSERT INTO families (id, name, created_by, created_at, updated_at) "
                + "VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                1L, "测试家庭", 1L);

        FamilyMember member = new FamilyMember();
        member.setFamilyId(1L);
        member.setUserId(1L);
        member.setRole(com.familyapp.family.FamilyRole.OWNER);
        member.setJoinedAt(now);
        familyMemberRepository.saveAndFlush(member);
    }

    @Test
    void POST_create_201_envelope_ok() throws Exception {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("wash dishes");
        req.setDueDate(LocalDate.of(2026, 8, 20));
        mvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.title").value("wash dishes"))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.priority").value("MEDIUM"))
            .andExpect(jsonPath("$.data.source").value("MANUAL"))
            .andExpect(jsonPath("$.data.id").isNumber());
    }

    @Test
    void POST_create_rejects_blank_title_40001() throws Exception {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("   ");
        mvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(40099))
            .andExpect(jsonPath("$.msg", notNullValue()));
    }

    @Test
    void GET_list_empty_array() throws Exception {
        mvc.perform(get("/api/tasks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void GET_list_with_status_filter() throws Exception {
        CreateTaskRequest cr = new CreateTaskRequest();
        cr.setTitle("filter me");
        mvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(cr)))
            .andExpect(status().isCreated());

        mvc.perform(get("/api/tasks").param("status", "PENDING"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].status", everyItem(equalTo("PENDING"))));
    }

    @Test
    void GET_by_id_not_found_returns_40401() throws Exception {
        mvc.perform(get("/api/tasks/99999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    void PUT_then_GET_returns_updated() throws Exception {
        CreateTaskRequest cr = new CreateTaskRequest();
        cr.setTitle("one");
        String createResp = mvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(cr)))
            .andReturn().getResponse().getContentAsString();
        TaskResponse created = json.readValue(
            json.readTree(createResp).get("data").toString(), TaskResponse.class);
        Long id = created.id();

        UpdateTaskRequest ur = new UpdateTaskRequest();
        ur.setStatus("COMPLETED");
        mvc.perform(put("/api/tasks/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(ur)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        UpdateTaskRequest bad = new UpdateTaskRequest();
        bad.setStatus("PENDING");
        mvc.perform(put("/api/tasks/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(bad)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(40011));
    }

    @Test
    void DELETE_hides_from_GET_list_soft_delete() throws Exception {
        CreateTaskRequest cr = new CreateTaskRequest();
        cr.setTitle("to delete");
        String body = mvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(cr)))
            .andReturn().getResponse().getContentAsString();
        Long id = json.readTree(body).get("data").get("id").longValue();

        mvc.perform(delete("/api/tasks/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data").value(true));

        mvc.perform(get("/api/tasks"))
            .andExpect(jsonPath("$.data[*].title", not(hasItem("to delete"))));
    }
}
