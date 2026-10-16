package com.familyapp.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyapp.task.Task;
import com.familyapp.task.TaskRepository;
import com.familyapp.task.TaskStatus;
import com.familyapp.wechat.WechatNotifyDtos.WechatNotifyMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Change 12 三触发点接线集成测试（@MockBean 替换 WechatNotifyClient：
 * 服务/接收人解析走真实 H2，仅出站 HTTP 被 mock 捕获）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "internal-api-test"})
@TestPropertySource(properties = "internal-api.secret=test-secret-correct")
@Transactional
class WechatNotifyFlowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET = "test-secret-correct";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskRepository taskRepository;
    @Autowired WechatNotifyJobs jobs;
    @Autowired TaskNotificationJob notificationJob;

    @MockBean WechatNotifyClient client;

    private JsonNode registerUser(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                        "email", email, "password", "password123", "nickname", "推送测试用户"))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body).get("data");
    }

    private long familyIdOf(long userId) {
        Long fid = jdbc.queryForObject(
                "SELECT family_id FROM family_members WHERE user_id = ?", Long.class, userId);
        assertThat(fid).isNotNull();
        return fid;
    }

    private void bindWechat(long userId, String openid) {
        jdbc.update("INSERT INTO wechat_bind(openid, user_id, bound_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                openid, userId);
    }

    private long claimSummaryLog(long familyId) throws Exception {
        String body = mvc.perform(post("/api/internal/agent/summary-log/claim")
                .header("X-Internal-Secret", SECRET)
                .param("family_id", String.valueOf(familyId))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body).get("data").get("log_id").asLong();
    }

    private void finishSummaryLog(long familyId, long logId, String runStatus) throws Exception {
        mvc.perform(put("/api/internal/agent/summary-log/" + logId)
                .header("X-Internal-Secret", SECRET)
                .param("family_id", String.valueOf(familyId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("status", runStatus))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
    }

    @SuppressWarnings("unchecked")
    private List<WechatNotifyMessage> capturedMessages() {
        ArgumentCaptor<List<WechatNotifyMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(client).sendMessages(captor.capture());
        return captor.getValue();
    }

    private Task seedTask(long familyId, LocalDate dueDate, TaskStatus status) {
        Task t = new Task();
        t.setFamilyId(familyId);
        t.setTitle("早报统计任务-" + status + "-" + dueDate);
        t.setDueDate(dueDate);
        t.setStatus(status);
        return taskRepository.save(t);
    }

    @Test
    @DisplayName("C-20 finish success：已绑定成员收到 summary_ready 模板消息")
    void summaryFinishSuccess_pushesToBoundMembers() throws Exception {
        JsonNode user = registerUser("c12-flow-a@example.com");
        long uid = user.get("userId").asLong();
        long fid = familyIdOf(uid);
        bindWechat(uid, "o-c12-flow-a");

        long logId = claimSummaryLog(fid);
        finishSummaryLog(fid, logId, "success");

        List<WechatNotifyMessage> messages = capturedMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).event()).isEqualTo("summary_ready");
        assertThat(messages.get(0).openid()).isEqualTo("o-c12-flow-a");
        assertThat(messages.get(0).params()).containsKey("family_name").containsKey("date");
    }

    @Test
    @DisplayName("C-20 finish failed：不发送任何消息")
    void summaryFinishFailed_noPush() throws Exception {
        JsonNode user = registerUser("c12-flow-b@example.com");
        long fid = familyIdOf(user.get("userId").asLong());
        bindWechat(user.get("userId").asLong(), "o-c12-flow-b");

        long logId = claimSummaryLog(fid);
        finishSummaryLog(fid, logId, "failed");

        verify(client, never()).sendMessages(any());
    }

    @Test
    @DisplayName("API 创建任务指派给已绑定成员：收到 task_assigned 消息")
    void taskCreateWithAssignee_pushes() throws Exception {
        JsonNode user = registerUser("c12-flow-c@example.com");
        long uid = user.get("userId").asLong();
        long fid = familyIdOf(uid);
        bindWechat(uid, "o-c12-flow-c");
        String token = user.get("accessToken").asText();

        mvc.perform(post("/api/tasks")
                .header("X-Access-Token", token)
                .header("X-Family-Id", String.valueOf(fid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                        "title", "买牛奶", "assigneeId", uid, "dueDate", "2026-09-10"))))
            .andExpect(jsonPath("$.code").value(0));

        verify(client, never()).sendMessages(any());
        notificationJob.dispatchFamily(fid);
        List<WechatNotifyMessage> messages = capturedMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).event()).isEqualTo("task_assigned");
        assertThat(messages.get(0).openid()).isEqualTo("o-c12-flow-c");
        assertThat(messages.get(0).params()).containsEntry("title", "买牛奶")
                .containsEntry("due_date", "2026-09-10");
    }

    @Test
    @DisplayName("推送链路异常（wechat-service 宕机）：总结收尾与任务创建仍成功")
    void clientThrowing_doesNotBreakBusiness() throws Exception {
        when(client.sendMessages(any())).thenThrow(new RuntimeException("模拟 wechat-service 宕机"));

        JsonNode user = registerUser("c12-flow-d@example.com");
        long uid = user.get("userId").asLong();
        long fid = familyIdOf(uid);
        bindWechat(uid, "o-c12-flow-d");

        long logId = claimSummaryLog(fid);
        finishSummaryLog(fid, logId, "success");  // 不抛即通过

        mvc.perform(post("/api/tasks")
                .header("X-Access-Token", user.get("accessToken").asText())
                .header("X-Family-Id", String.valueOf(fid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("title", "隔离测试任务", "assigneeId", uid))))
            .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("08:00 早报 job：今日到期 2 / 逾期 1，COMPLETED 排除，已绑定成员收到 task_digest")
    void digestJob_countsTodayAndOverdueExcludesCompleted() throws Exception {
        JsonNode user = registerUser("c12-flow-e@example.com");
        long uid = user.get("userId").asLong();
        long fid = familyIdOf(uid);
        bindWechat(uid, "o-c12-flow-e");

        LocalDate today = LocalDate.now();
        seedTask(fid, today, TaskStatus.PENDING);
        seedTask(fid, today, TaskStatus.PENDING);
        seedTask(fid, today.minusDays(1), TaskStatus.PENDING);   // 逾期
        seedTask(fid, today, TaskStatus.COMPLETED);              // 完成不计

        jobs.dailyDigestJob();

        List<WechatNotifyMessage> messages = capturedMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).event()).isEqualTo("task_digest");
        assertThat(messages.get(0).openid()).isEqualTo("o-c12-flow-e");
        assertThat(messages.get(0).params()).containsEntry("today_due", 2L)
                .containsEntry("overdue", 1L);
    }
}
