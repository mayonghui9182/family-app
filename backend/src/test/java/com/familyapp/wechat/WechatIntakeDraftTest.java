package com.familyapp.wechat;

import com.familyapp.agent.AgentCallbackClient;
import com.familyapp.agent.AgentCallbackClient.DraftSubtask;
import com.familyapp.agent.DecomposeSession;
import com.familyapp.agent.DecomposeSessionRepository;
import com.familyapp.agent.DecomposeSessionService;
import com.familyapp.auth.dto.LoginResponse;
import com.familyapp.config.FeatureGuardConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入站消息 → AI 拆解草稿测试（Change 14 Task 4，design.md D5）。
 *
 * <p>钉住三件事：
 * <ol>
 *   <li><b>草稿承载方式</b>：用户原文进 {@code init_task_title}（D5 选定的列），
 *       经既有 D-1 {@code createDraft} 落 {@code ACTIVE}，{@code family_id} 用受理侧裁决出的家庭；</li>
 *   <li><b>不给死列引入写入</b>：{@code messages} 保持 {@code []}。该列全仓 main 只有
 *       {@code createDraft} 一处 {@code setMessages(new ArrayList<>())}，本 change 不发明新契约；</li>
 *   <li><b>草稿期零业务表写入铁律</b>：confirm 之前 {@code tasks} 表一行不写。</li>
 * </ol>
 *
 * <p>与 Task 3 的 {@code WechatIntakeJobTest} 分工：那边用 mock 把作业状态机与外呼时序钉死，
 * 这边全部走真实落库（真实 {@code WechatIntakeService} + 真实 {@code DecomposeSessionService} + 真实 H2），
 * 用例⑤⑥ 把两者串起来验证端到端落库。
 *
 * <p>Task 6b 起，作业调的编排是 {@code decomposeInbound}，它在建草稿之后还要外呼 A-2。
 * 因此这两个用例只把<b>两个外呼协作方</b>换成替身（取票据的 {@code WechatService}、调 agent-service 的
 * {@code AgentCallbackClient}），数据库与草稿落库全部真实——「失败回队后复用同一份草稿」这件事
 * 只有真库才证得了：它依赖 {@code attachSession} 在事务外真的提交了。
 */
@SpringBootTest
@ActiveProfiles("test")
class WechatIntakeDraftTest {

    private static final int MAX_ATTEMPTS = 3;
    /** 用专属 family_id 圈定本类的清理范围，避免动到其他测试类的既有行。 */
    private static final long 裁决家庭 = 9100L;
    private static final long 裁决用户 = 9101L;
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired WechatIntakeService intakeService;
    @Autowired WechatInboundMessageRepository inboundRepo;
    @Autowired DecomposeSessionRepository sessionRepo;
    @Autowired DecomposeSessionService sessionService;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired FeatureGuardConfig aiGuard;

    @BeforeEach
    void clearScopedRows() {
        jdbc.update("DELETE FROM agent_decompose_sessions WHERE family_id = ?", 裁决家庭);
        jdbc.update("DELETE FROM wechat_inbound_message WHERE openid = ?", "o-draft-test");
    }

    /** 造一行受理侧裁决完成的入站消息（内存对象，不依赖 content 列宽）。 */
    private WechatInboundMessage 入站行(String content) {
        WechatInboundMessage row = new WechatInboundMessage();
        row.setMsgId("draft-" + SEQ.incrementAndGet());
        row.setOpenid("o-draft-test");
        row.setContent(content);
        row.setReceivedAt(Instant.now());
        row.setStatus(WechatInboundStatus.RUNNING);
        row.setAttempts(1);
        row.setFamilyId(裁决家庭);
        row.setUserId(裁决用户);
        return row;
    }

    private Map<String, Object> 草稿行(Long sessionId) {
        return jdbc.queryForMap(
            "SELECT status, session_type, init_task_title, family_id, created_by"
                + " FROM agent_decompose_sessions WHERE id = ?",
            sessionId);
    }

    private int 草稿份数() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM agent_decompose_sessions WHERE family_id = ?", Integer.class, 裁决家庭);
        return count == null ? 0 : count.intValue();
    }

    /** 取票据替身：真实 {@code WechatService} 要先有 users/wechat_bind 行，这里只替掉这一件事。 */
    private WechatService 绑定替身() {
        WechatService 替身 = mock(WechatService.class);
        when(替身.loginByOpenid("o-draft-test")).thenReturn(Optional.of(
            new LoginResponse("draft-access-token", 裁决用户, "小美", List.of())));
        return 替身;
    }

    /** 用真实仓储 + 真实草稿服务组装服务，只把两个外呼协作方换成替身。 */
    private WechatIntakeService 组装服务(AgentCallbackClient 拆解替身) {
        return new WechatIntakeService(绑定替身(), inboundRepo, sessionService, 拆解替身,
            mock(WechatNotifyClient.class), "http://h5.test", true);
    }

    private AgentCallbackClient 拆解成功替身() {
        AgentCallbackClient 替身 = mock(AgentCallbackClient.class);
        when(替身.decomposeDraft(anyLong(), anyString(), anyString(), anyLong()))
            .thenReturn(List.of(new DraftSubtask("挂号", "预约心内科门诊", 1)));
        return 替身;
    }

    private WechatIntakeJob 作业(WechatIntakeService 服务) {
        return new WechatIntakeJob(inboundRepo, 服务, aiGuard, txManager, true, MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("① 原文建草稿：ACTIVE + TASK_CREATE + init_task_title 等于原文 + family_id 等于裁决家庭")
    void createsActiveTaskCreateDraftWithRawTextAsTitle() {
        Long sessionId = intakeService.createDraftFromMessage(入站行("周六带爸去复查"));
        assertNotNull(sessionId, "应返回新建草稿的 session_id");

        Map<String, Object> draft = 草稿行(sessionId);
        assertEquals("ACTIVE", draft.get("status"));
        assertEquals("TASK_CREATE", draft.get("session_type"));
        assertEquals("周六带爸去复查", draft.get("init_task_title"));
        assertEquals(裁决家庭, ((Number) draft.get("family_id")).longValue(),
            "草稿必须落在受理侧裁决出的家庭，否则违反 family_id 硬隔离");
        assertEquals(裁决用户, ((Number) draft.get("created_by")).longValue());
    }

    @Test
    @DisplayName("② 超长原文建草稿：init_task_title 截断到 200，不撑破列宽")
    void truncatesOverlongContentToTitleColumnWidth() {
        String 原文 = "带".repeat(250);
        Long sessionId = intakeService.createDraftFromMessage(入站行(原文));

        String title = (String) 草稿行(sessionId).get("init_task_title");
        assertEquals(200, title.length(), "受理侧已截断，这里再兜一层：D-1 落库前同样按 200 截断");
    }

    @Test
    @DisplayName("③ 草稿的 messages 列保持空数组（不给死列引入新写入方）")
    void leavesMessagesColumnEmpty() {
        Long sessionId = intakeService.createDraftFromMessage(入站行("周六带爸去复查"));

        DecomposeSession draft = sessionRepo.findById(sessionId).orElseThrow();
        assertNotNull(draft.getMessages());
        assertTrue(draft.getMessages().isEmpty(),
            "messages 是全仓从未写入过的死列，本 change 不得给它引入语义（design.md D5）");
    }

    @Test
    @DisplayName("④ 建草稿不写业务表：tasks 行数前后不变")
    void doesNotTouchBusinessTablesDuringDrafting() {
        int before = countTasks();
        intakeService.createDraftFromMessage(入站行("周六带爸去复查"));
        assertEquals(before, countTasks(), "confirm 之前 tasks 表一行不写（草稿箱-原子确认模式）");
    }

    @Test
    @DisplayName("⑤ 作业串接拆解：入站行落 DONE、session_id 指向真实 ACTIVE 草稿、A-2 带该 id")
    void jobPersistsRealDraftIdOnInboundRow() {
        WechatInboundMessage row = 入站行("周六带爸去复查");
        row.setStatus(WechatInboundStatus.PENDING);
        row.setAttempts(0);
        inboundRepo.save(row);
        AgentCallbackClient 拆解替身 = 拆解成功替身();

        作业(组装服务(拆解替身)).pollInboundQueue();

        Map<String, Object> 结果 = jdbc.queryForMap(
            "SELECT status, session_id FROM wechat_inbound_message WHERE msg_id = ?", row.getMsgId());
        assertEquals("DONE", 结果.get("status"));
        Long sessionId = ((Number) 结果.get("session_id")).longValue();
        assertEquals("ACTIVE", 草稿行(sessionId).get("status"),
            "回写的 session_id 必须指向真实存在的草稿，否则回推链接是死链");

        ArgumentCaptor<Long> 外呼草稿 = ArgumentCaptor.forClass(Long.class);
        verify(拆解替身).decomposeDraft(eq(裁决家庭), anyString(), eq("周六带爸去复查"), 外呼草稿.capture());
        assertEquals(sessionId, 外呼草稿.getValue().longValue(),
            "外呼带的 session_id 必须就是回写的那个，否则 proposed_tasks 落在别的草稿上");
        assertEquals(0, countTasks(), "拆解只填 proposed_tasks，tasks 仍零写入（D5）");
    }

    @Test
    @DisplayName("⑥ 首轮拆解失败 → 行回 PENDING 但 session_id 已挂上；次轮复用同一份草稿，全程只 1 份")
    void failedFirstRoundReusesTheSameDraftOnRetry() {
        WechatInboundMessage row = 入站行("周六带爸去复查");
        row.setStatus(WechatInboundStatus.PENDING);
        row.setAttempts(0);
        inboundRepo.save(row);

        AtomicInteger 外呼轮次 = new AtomicInteger();
        AgentCallbackClient 拆解替身 = mock(AgentCallbackClient.class);
        when(拆解替身.decomposeDraft(anyLong(), anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            if (外呼轮次.incrementAndGet() == 1) {
                throw new IllegalStateException("草稿拆解失败：agent-service 不可达");
            }
            return List.of(new DraftSubtask("挂号", "预约心内科门诊", 1));
        });
        WechatIntakeJob 作业 = 作业(组装服务(拆解替身));

        作业.pollInboundQueue();

        Map<String, Object> 首轮 = jdbc.queryForMap(
            "SELECT status, attempts, session_id, last_error FROM wechat_inbound_message WHERE msg_id = ?",
            row.getMsgId());
        assertEquals("PENDING", 首轮.get("status"), "未耗尽重试额度应回队（D8）");
        assertEquals(1, ((Number) 首轮.get("attempts")).intValue());
        assertNotNull(首轮.get("session_id"), "草稿指针必须在中转时就提交，否则次轮会再建一份草稿");
        Long 首轮草稿 = ((Number) 首轮.get("session_id")).longValue();
        assertEquals(1, 草稿份数());

        作业.pollInboundQueue();

        Map<String, Object> 次轮 = jdbc.queryForMap(
            "SELECT status, session_id FROM wechat_inbound_message WHERE msg_id = ?", row.getMsgId());
        assertEquals("DONE", 次轮.get("status"));
        assertEquals(首轮草稿, ((Number) 次轮.get("session_id")).longValue(), "次轮必须复用首轮那份草稿");
        assertEquals(1, 草稿份数(), "一条消息最多一份草稿：多出来的每一份都是用户收到的一条重复推送");
        assertEquals(2, 外呼轮次.get());
    }

    private int countTasks() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tasks", Integer.class);
        return count == null ? 0 : count.intValue();
    }
}
