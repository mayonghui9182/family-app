package com.familyapp.category;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyapp.category.dto.CategoryResponse;
import com.familyapp.category.dto.CreateCategoryRequest;
import com.familyapp.category.dto.UpdateCategoryRequest;
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
@ActiveProfiles("test")
class CategoryControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void GET_list_returns_array() throws Exception {
        mvc.perform(get("/api/categories"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void POST_create_201_with_defaults() throws Exception {
        CreateCategoryRequest req = new CreateCategoryRequest();
        req.setName("家务");
        mvc.perform(post("/api/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.name").value("家务"))
            .andExpect(jsonPath("$.data.color").value("#909399"))
            .andExpect(jsonPath("$.data.sortOrder").value(0))
            .andExpect(jsonPath("$.data.familyId").value(1))
            .andExpect(jsonPath("$.data.id").isNumber());
    }

    @Test
    void POST_create_rejects_duplicate_name_40021() throws Exception {
        CreateCategoryRequest req = new CreateCategoryRequest();
        req.setName("育儿");
        mvc.perform(post("/api/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isCreated());

        mvc.perform(post("/api/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(40021));
    }

    @Test
    void POST_create_rejects_blank_name_40099() throws Exception {
        CreateCategoryRequest req = new CreateCategoryRequest();
        req.setName("   ");
        mvc.perform(post("/api/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(40099));
    }

    @Test
    void GET_list_after_create_contains_item() throws Exception {
        CreateCategoryRequest req = new CreateCategoryRequest();
        req.setName("工作");
        req.setColor("#409EFF");
        req.setSortOrder(3);
        mvc.perform(post("/api/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isCreated());

        mvc.perform(get("/api/categories"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].name", hasItem("工作")));
    }

    @Test
    void GET_by_id_not_found_returns_40402() throws Exception {
        mvc.perform(get("/api/categories/99999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(40402));
    }

    @Test
    void PUT_update_changes_color() throws Exception {
        CreateCategoryRequest req = new CreateCategoryRequest();
        req.setName("财务");
        String body = mvc.perform(post("/api/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn().getResponse().getContentAsString();
        Long id = json.readTree(body).get("data").get("id").longValue();

        UpdateCategoryRequest ur = new UpdateCategoryRequest();
        ur.setColor("#F56C6C");
        mvc.perform(put("/api/categories/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(ur)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.color").value("#F56C6C"));
    }

    @Test
    void DELETE_empty_category_returns_ok() throws Exception {
        CreateCategoryRequest req = new CreateCategoryRequest();
        req.setName("toDelete");
        String body = mvc.perform(post("/api/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn().getResponse().getContentAsString();
        Long id = json.readTree(body).get("data").get("id").longValue();

        mvc.perform(delete("/api/categories/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        mvc.perform(get("/api/categories"))
            .andExpect(jsonPath("$.data[*].name", not(hasItem("toDelete"))));
    }
}