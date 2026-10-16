package com.familyapp.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyapp.profile.dto.CreatePersonProfileRequest;
import com.familyapp.profile.dto.UpdatePersonProfileRequest;
import com.familyapp.task.Task;
import com.familyapp.task.TaskRepository;
import com.familyapp.task.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 人员档案 P-1~P-6 端点集成测试
 *
 * <p>验证对象：PersonProfileController 6 端点 + ProfileRoleGuard 权限守卫
 *
 * <p>设计依据（spec §2 P-1~P-6 + plan Step 1c 第 3 项）：
 * <ol>
 *   <li>P-1 GET /api/v1/person-profiles 列档案（query type 可过滤）→ 全家可读</li>
 *   <li>P-2 POST /api/v1/person-profiles 新建 → OWNER/ADULT；CHILD=40303</li>
 *   <li>P-3 GET /api/v1/person-profiles/{id} 详情 → 家庭内可读</li>
 *   <li>P-4 PUT /api/v1/person-profiles/{id} 更新（含 remove_keys[]）→ OWNER/ADULT</li>
 *   <li>P-5 DELETE /api/v1/person-profiles/{id} 软删除 → OWNER；待办绑定=40901</li>
 *   <li>P-6 GET /api/v1/person-profiles/options 下拉 → 返回 profileTypeName 中文</li>
 * </ol>
 *
 * <p>权限矩阵（spec §2 P-2/P-4/P-5 + plan §B 核心决策）：
 * <ul>
 *   <li>CHILD 角色 POST/PUT/DELETE → 40303「儿童无权限修改家庭档案」</li>
 *   <li>非 OWNER 角色（MEMBER）DELETE → 40304「仅家庭主人可删除档案」</li>
 *   <li>档案有待办(PENDING)绑定 DELETE → 40901「档案有待办任务，无法删除」（Step 1e 独立测试）</li>
 * </ul>
 *
 * <p>角色切换机制：TestUserContextFilter 通过 X-Test-Role 请求头注入角色（默认 OWNER），
 * 测试中通过 .header("X-Test-Role", "CHILD") 切换 CHILD，"MEMBER" 切换非 OWNER。
 *
 * <p>Service 层兜底（spec §1.1 + plan §B 核心决策 5）：
 * 若 request.avatarEmoji 为空，Service 按 profile_type 赋值：BABY→👶 ELDER→👴 ADULT→👨 PET→🐾 OTHER→👤
 *
 * <p>TDD 状态：
 * <ul>
 *   <li>RED 阶段（Step 1c）：PersonProfileController/Service/DTO 类不存在 → 编译失败 → 8 用例全 RED</li>
 *   <li>GREEN 阶段（Step 8）：Step 5-7 写完实体/DTO/Controller/Service → 全 PASS</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("classpath:db/v6-test-setup.sql") // 预置 family=1 + user=1 + 4 分类，与 v6 测试套件共享种子
class PersonProfileControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private TaskRepository taskRepository;

    // ==================== P-1 / P-6 查询类（OWNER 默认可读） ====================

    @Test
    @DisplayName("1. P-1 GET /api/v1/person-profiles 列档案 → 200 返回数组")
    void p1_list_返回数组() throws Exception {
        mvc.perform(get("/api/v1/person-profiles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("2. P-6 GET /api/v1/person-profiles/options 下拉 → 200 返回 profileTypeName 中文（宝宝/老人/成人/宠物/其他）")
    void p6_options_返回profileTypeName中文() throws Exception {
        mvc.perform(get("/api/v1/person-profiles/options"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data[*].profileTypeName", hasItem("宝宝")))
            .andExpect(jsonPath("$.data[*].profileTypeName", hasItem("老人")))
            .andExpect(jsonPath("$.data[*].profileTypeName", hasItem("成人")))
            .andExpect(jsonPath("$.data[*].profileTypeName", hasItem("宠物")))
            .andExpect(jsonPath("$.data[*].profileTypeName", hasItem("其他")));
    }

    // ==================== P-2 POST 新建（OWNER/ADULT；CHILD=40303） ====================

    @Test
    @DisplayName("3. P-2 OWNER POST 建宝宝档案 → 201 + avatar_emoji Service 兜底👶 + profile_type=BABY")
    void p2_owner_建宝宝档案_201_emoji兜底() throws Exception {
        CreatePersonProfileRequest req = new CreatePersonProfileRequest();
        req.setProfileType("BABY");
        req.setName("小宝");
        req.setRelation("儿子");
        // 不传 avatarEmoji，验证 Service 层按 profile_type 兜底赋值 👶

        mvc.perform(post("/api/v1/person-profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.profileType").value("BABY"))
            .andExpect(jsonPath("$.data.name").value("小宝"))
            .andExpect(jsonPath("$.data.avatarEmoji").value("👶"))
            .andExpect(jsonPath("$.data.id").isNumber());
    }

    @Test
    @DisplayName("4. CHILD 角色 POST → 40303「儿童无权限修改家庭档案」")
    void child_post_返回40303() throws Exception {
        CreatePersonProfileRequest req = new CreatePersonProfileRequest();
        req.setProfileType("BABY");
        req.setName(" unauthorized");
        req.setRelation("儿子");

        mvc.perform(post("/api/v1/person-profiles")
                .header("X-Test-Role", "CHILD")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(40303));
    }

    // ==================== P-3 GET 详情（家庭内可读） ====================

    @Test
    @DisplayName("5. P-3 GET /api/v1/person-profiles/{id} 详情 → 200 + 字段完整")
    void p3_get_详情_200_字段完整() throws Exception {
        // 先 POST 建档拿到 id，再 GET 详情
        CreatePersonProfileRequest req = new CreatePersonProfileRequest();
        req.setProfileType("ELDER");
        req.setName("爷爷");
        req.setRelation("父亲");
        req.setAvatarEmoji("👴");

        String created = mvc.perform(post("/api/v1/person-profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn().getResponse().getContentAsString();
        Long id = json.readTree(created).at("/data/id").asLong();

        mvc.perform(get("/api/v1/person-profiles/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.id").value(id))
            .andExpect(jsonPath("$.data.profileType").value("ELDER"))
            .andExpect(jsonPath("$.data.name").value("爷爷"))
            .andExpect(jsonPath("$.data.relation").value("父亲"));
    }

    // ==================== P-4 PUT 更新（含 remove_keys） ====================

    @Test
    @DisplayName("6. P-4 PUT /api/v1/person-profiles/{id} 更新 + remove_keys 删除 → 200")
    void p4_put_更新_含removeKeys_200() throws Exception {
        // 先建宝宝档案 + 写入 month_age 和 allergy_list
        CreatePersonProfileRequest req = new CreatePersonProfileRequest();
        req.setProfileType("BABY");
        req.setName("小宝");
        req.setRelation("儿子");
        req.setProfileData(Map.of("month_age", 6, "allergy_list", List.of("牛奶")));

        String created = mvc.perform(post("/api/v1/person-profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn().getResponse().getContentAsString();
        Long id = json.readTree(created).at("/data/id").asLong();

        // PUT 更新 month_age=9 + remove_keys 删 allergy_list
        UpdatePersonProfileRequest update = new UpdatePersonProfileRequest();
        update.setProfileDataPatch(Map.of("month_age", 9));
        update.setRemoveKeys(List.of("allergy_list"));

        mvc.perform(put("/api/v1/person-profiles/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(update)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.profileData.month_age").value(9));
    }

    // ==================== P-5 DELETE 软删除（OWNER；非 OWNER=40304） ====================

    @Test
    @DisplayName("7. P-5 OWNER DELETE /api/v1/person-profiles/{id} → 204 软删除")
    void p5_owner_delete_204() throws Exception {
        CreatePersonProfileRequest req = new CreatePersonProfileRequest();
        req.setProfileType("PET");
        req.setName("旺财");
        req.setRelation("宠物");
        req.setAvatarEmoji("🐾");

        String created = mvc.perform(post("/api/v1/person-profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn().getResponse().getContentAsString();
        Long id = json.readTree(created).at("/data/id").asLong();

        mvc.perform(delete("/api/v1/person-profiles/" + id))
            .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("8. 非 OWNER（MEMBER）DELETE → 40304「仅家庭主人可删除档案」")
    void member_delete_返回40304() throws Exception {
        // OWNER 先建档
        CreatePersonProfileRequest req = new CreatePersonProfileRequest();
        req.setProfileType("OTHER");
        req.setName("测试档案");
        req.setRelation("其他");

        String created = mvc.perform(post("/api/v1/person-profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn().getResponse().getContentAsString();
        Long id = json.readTree(created).at("/data/id").asLong();

        // MEMBER 角色尝试删除 → 40304
        mvc.perform(delete("/api/v1/person-profiles/" + id)
                .header("X-Test-Role", "MEMBER"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(40304));
    }

    @Test
    @DisplayName("9. P-5 有待办任务绑定时 DELETE → 409 + 41007，档案保留")
    void p5_active_task_assigned_profile_cannot_delete() throws Exception {
        CreatePersonProfileRequest req = new CreatePersonProfileRequest();
        req.setProfileType("ELDER");
        req.setName("待办绑定档案");
        req.setRelation("父亲");
        String created = mvc.perform(post("/api/v1/person-profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn().getResponse().getContentAsString();
        Long profileId = json.readTree(created).at("/data/id").asLong();

        Task task = new Task();
        task.setTitle("提醒吃药");
        task.setFamilyId(1L);
        task.setCreatedBy(1L);
        task.setAssigneeProfileId(profileId);
        task.setStatus(TaskStatus.PENDING);
        taskRepository.save(task);

        mvc.perform(delete("/api/v1/person-profiles/" + profileId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(41007));

        mvc.perform(get("/api/v1/person-profiles/" + profileId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(profileId));
    }
}
