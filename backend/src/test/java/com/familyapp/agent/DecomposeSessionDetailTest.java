package com.familyapp.agent;

import com.familyapp.config.TestUserContextFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Change 14 Task 11 · D-8 草稿详情读取端点（GET /api/agent/v1/decompose/sessions/{id}）
 *
 * <p>覆盖 tasks.md Step 1 的 ①~④⑥ + 自增用例：
 * <ol>
 *   <li>① 本家庭 ACTIVE 草稿 → 200 + {id,status,session_type,init_task_title,proposed_tasks}</li>
 *   <li>② 跨 family_id 访问 → 46001（不可见，不泄漏任何草稿内容）</li>
 *   <li>③ CONFIRMED 可读（用户重复点深链要看到「已创建」态，而不是 404）</li>
 *   <li>④ CANCELLED 可读并标 status</li>
 *   <li>④b ARCHIVED 不可读 → 50311（终态话术原文含「归档」）</li>
 *   <li>⑤ 见 DecomposeSessionDetailDisabledTest（@TestPropertySource 是类级注解，须独立成类）</li>
 *   <li>⑥ proposed_tasks 恰 20 条 → 全量透传不截断</li>
 *   <li>自增：不存在的 id → 46001（与跨家庭同码，不区分「不存在/别人的」防枚举）</li>
 * </ol>
 *
 * <p>偏离说明：响应省略规格原文里的 createdTaskIds[]——该数据在当前 schema 无落库来源
 * （confirm 里是局部变量，仅出现在 D-5 响应体），「已创建」态由 status=CONFIRMED 驱动。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("classpath:db/v6-test-setup.sql")
class DecomposeSessionDetailTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private DecomposeSessionRepository sessionRepo;

    private DecomposeSession seed(DecomposeSessionStatus status, List<Object> proposedTasks) {
        var s = new DecomposeSession();
        s.setFamilyId(1L);
        s.setSessionType(DecomposeSessionType.TASK_CREATE);
        s.setStatus(status);
        s.setInitTaskTitle("周六上午带爸爸去医院复查");
        s.setInitProfileIdSnapshot(new HashMap<>());
        s.setMessages(new ArrayList<>());
        s.setProposedTasks(proposedTasks);
        s.setCreatedBy(1L);
        return sessionRepo.save(s);
    }

    // ==================== ① 本家庭 ACTIVE 草稿 → 200 + 契约字段 ====================

    @Test
    @DisplayName("D8-① 本家庭 ACTIVE 草稿 → 200 + {id,status,session_type,init_task_title,proposed_tasks}")
    void d81_本家庭ACTIVE草稿_返回200与契约字段() throws Exception {
        DecomposeSession seed = seed(DecomposeSessionStatus.ACTIVE, List.of(
                Map.of("title", "准备复查病历本", "due_date", "2026-09-18"),
                Map.of("title", "预约周六上午的车", "due_date", "2026-09-17")
        ));

        mvc.perform(get("/api/agent/v1/decompose/sessions/" + seed.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(seed.getId().intValue()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.session_type").value("TASK_CREATE"))
                .andExpect(jsonPath("$.data.init_task_title").value("周六上午带爸爸去医院复查"))
                .andExpect(jsonPath("$.data.proposed_tasks.length()").value(2))
                .andExpect(jsonPath("$.data.proposed_tasks[0].title").value("准备复查病历本"));
    }

    // ==================== ② 跨家庭访问 → 46001 不可见 ====================

    @Test
    @DisplayName("D8-② 草稿属 family 1，以 family 2 身份 GET → 46001，不泄漏任何内容")
    void d82_跨family越权_返回46001不泄漏内容() throws Exception {
        DecomposeSession seed = seed(DecomposeSessionStatus.ACTIVE,
                List.of(Map.of("title", "别家庭不可见的任务")));

        mvc.perform(get("/api/agent/v1/decompose/sessions/" + seed.getId())
                        .header("X-Test-Family-Id", 2L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(46001))
                .andExpect(jsonPath("$.data").doesNotExist());

        // 反向对照：同 id 用默认 family 1（TestUserContextFilter 缺省值）访问必须 200，
        // 证明 46001 来自 family 作用域判断而非端点缺失。
        mvc.perform(get("/api/agent/v1/decompose/sessions/" + seed.getId())
                        .header("X-Test-Family-Id", TestUserContextFilter.DEFAULT_FAMILY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    // ==================== ③ CONFIRMED 可读（重复点深链要看「已创建」） ====================

    @Test
    @DisplayName("D8-③ CONFIRMED 草稿可读 → 200 + status=CONFIRMED + 草稿清单保留")
    void d83_CONFIRMED草稿_仍可读200() throws Exception {
        DecomposeSession seed = seed(DecomposeSessionStatus.CONFIRMED,
                List.of(Map.of("title", "已经确认创建过的任务")));

        mvc.perform(get("/api/agent/v1/decompose/sessions/" + seed.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.proposed_tasks.length()").value(1));
    }

    // ==================== ④ CANCELLED 可读并标 status ====================

    @Test
    @DisplayName("D8-④ CANCELLED 草稿可读 → 200 + status=CANCELLED")
    void d84_CANCELLED草稿_可读并标状态() throws Exception {
        DecomposeSession seed = seed(DecomposeSessionStatus.CANCELLED,
                List.of(Map.of("title", "用户点了不要了的任务")));

        mvc.perform(get("/api/agent/v1/decompose/sessions/" + seed.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    // ==================== ④b ARCHIVED 不可读 → 50311 ====================

    @Test
    @DisplayName("D8-④b ARCHIVED 草稿 → 503 + 50311（50311 话术原文覆盖『归档』）")
    void d84b_ARCHIVED草稿_返回50311() throws Exception {
        DecomposeSession seed = seed(DecomposeSessionStatus.ARCHIVED,
                List.of(Map.of("title", "只存档案后归档的任务")));

        mvc.perform(get("/api/agent/v1/decompose/sessions/" + seed.getId()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50311))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ==================== ⑥ 20 条 proposed_tasks 全量透传 ====================

    @Test
    @DisplayName("D8-⑥ proposed_tasks 恰 20 条 → 全量透传不截断（20 是 confirm 上限，读取侧不得吞条）")
    void d86_二十条提案_全量透传() throws Exception {
        List<Object> twenty = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            twenty.add(Map.of("title", String.format("任务%02d", i)));
        }
        DecomposeSession seed = seed(DecomposeSessionStatus.ACTIVE, twenty);

        mvc.perform(get("/api/agent/v1/decompose/sessions/" + seed.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposed_tasks.length()").value(20))
                .andExpect(jsonPath("$.data.proposed_tasks[0].title").value("任务01"))
                .andExpect(jsonPath("$.data.proposed_tasks[19].title").value("任务20"));
    }

    // ==================== 自增：不存在的 id → 46001（与越权同码防枚举） ====================

    @Test
    @DisplayName("D8-自增 不存在的 id → 400 + 46001（与跨家庭同码，不区分『不存在/别人的』）")
    void d8x_不存在的id_返回46001() throws Exception {
        mvc.perform(get("/api/agent/v1/decompose/sessions/999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(46001))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
