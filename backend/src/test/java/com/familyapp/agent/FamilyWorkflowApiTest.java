package com.familyapp.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.MediaType;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("classpath:db/v6-test-setup.sql")
class FamilyWorkflowApiTest {
    @Autowired MockMvc mvc;
    @Autowired DecomposeSessionRepository sessions;
    @Autowired ObjectMapper mapper;
    private DecomposeSession seed() {
        DecomposeSession s = new DecomposeSession();
        s.setFamilyId(1L); s.setCreatedBy(1L); s.setSessionType(DecomposeSessionType.TASK_CREATE);
        s.setInitTaskTitle("周末买菜"); s.setProposedTasks(List.of(Map.of("title", "买青菜")));
        return sessions.saveAndFlush(s);
    }
    @Test void 修改草稿并确认后可追溯任务且不可重复确认() throws Exception {
        var s = seed(); String path = "/api/agent/v1/decompose/sessions/" + s.getId();
        mvc.perform(put(path + "/proposal").contentType(MediaType.APPLICATION_JSON).content("[{\"title\":\"买菠菜\"}]"))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(post(path + "/confirm")).andExpect(jsonPath("$.data.created_task_ids.length()").value(1));
        mvc.perform(get(path)).andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.proposed_tasks[0].title").value("买菠菜"))
                .andExpect(jsonPath("$.data.proposed_tasks[0].created_task_id").isNumber());
        mvc.perform(post(path + "/confirm")).andExpect(jsonPath("$.code").value(50311));
    }
    @Test void 保存草稿拒绝跨家庭及迟到版本() throws Exception {
        var s = seed(); String path = "/api/internal/agent/sessions/" + s.getId();
        mvc.perform(get(path + "/context").param("family_id", "2"))
                .andExpect(jsonPath("$.code").value(46001));
        String body = mapper.writeValueAsString(Map.of("proposed_tasks", List.of(Map.of("title", "其他安排")),
                "revision", "2000-01-01T00:00:00Z"));
        mvc.perform(post(path + "/save-proposal").param("family_id", "2")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(jsonPath("$.code").value(46001));
        mvc.perform(post(path + "/save-proposal").param("family_id", "1")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(jsonPath("$.code").value(50311));
    }
    @Test void 分页端点限制页大小且返回家庭结果() throws Exception {
        mvc.perform(get("/api/tasks/page").param("size", "5"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.size").value(5));
        mvc.perform(get("/api/tasks/page").param("size", "10000"))
                .andExpect(status().isBadRequest());
    }
}
