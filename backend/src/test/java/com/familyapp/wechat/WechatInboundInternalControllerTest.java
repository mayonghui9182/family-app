package com.familyapp.wechat;

import com.familyapp.family.FamilyMember;
import com.familyapp.family.FamilyMemberRepository;
import com.familyapp.family.FamilyRole;
import com.familyapp.family.FamilyService;
import com.familyapp.family.dto.CreateFamilyRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 微信入站消息受理端点测试（Change 14 Task 2）。
 *
 * <p>覆盖 design.md D3 裁决表的功能分支：受理、未绑定、无家庭、CHILD 拒绝、多家庭、
 * msg_id 幂等重推、超长内容截断。
 *
 * <p>Profile 选择说明：本类跑在 {@code test} Profile 下，{@code InternalSecretInterceptor}
 * 对 test Profile 直接放行（{@code isBypassProfile()}），因此这里可以专注裁决逻辑，
 * 秘钥守护另由 {@code WechatInboundSecretGuardTest} 独占验证；开关关闭分支另由
 * {@code WechatInboundIntakeDisabledTest} 独占验证（三类的 Profile / PropertySource 互斥）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "wechat.intake.enabled=true")
@Transactional
class WechatInboundInternalControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FamilyService familyService;
    @Autowired FamilyMemberRepository familyMemberRepository;

    /** 注册 → 取票据 → 绑定 openid，返回新用户 id。 */
    private long registerAndBind(String openid) throws Exception {
        String email = "intake" + SEQ.incrementAndGet() + "@example.com";
        String body = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                    "email", email, "password", "password123", "nickname", "入站用户"))))
            .andExpect(jsonPath("$.code").value(0))
            .andReturn().getResponse().getContentAsString();
        long userId = MAPPER.readTree(body).get("data").get("userId").asLong();

        String ticketBody = mvc.perform(post("/api/internal/wechat/ticket")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("openid", openid))))
            .andExpect(jsonPath("$.code").value(0))
            .andReturn().getResponse().getContentAsString();
        String ticket = MAPPER.readTree(ticketBody).get("data").get("ticket").asText();

        mvc.perform(post("/api/auth/wechat-bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                    "ticket", ticket, "email", email, "password", "password123"))))
            .andExpect(jsonPath("$.code").value(0));
        return userId;
    }

    private JsonNode 提交入站(String openid, String msgId, String content) throws Exception {
        String body = mvc.perform(post("/api/internal/wechat/inbound-message")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                    "openid", openid,
                    "msg_id", msgId,
                    "content", content,
                    "received_at", "2026-09-13T08:00:00Z"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body).get("data");
    }

    /** 把唯一活跃成员改成指定角色（照 FamilyControllerIntegrationTest 的既有手法）。 */
    private void 改角色(long userId, FamilyRole role) {
        List<FamilyMember> members = familyMemberRepository.findAllByUserIdAndLeftAtIsNull(userId);
        assertEquals(1, members.size(), "前置条件：应只有一个活跃家庭");
        FamilyMember member = members.get(0);
        member.setRole(role);
        familyMemberRepository.save(member);
    }

    @Test
    @DisplayName("① 已绑定 + 唯一 ADULT 家庭 → accepted=true 且落一行 PENDING")
    void singleAdultFamily_acceptedAsPending() throws Exception {
        String openid = "o-intake-adult-" + SEQ.incrementAndGet();
        long userId = registerAndBind(openid);
        改角色(userId, FamilyRole.ADULT);
        long familyId = jdbc.queryForObject(
            "SELECT family_id FROM family_members WHERE user_id = ? AND left_at IS NULL",
            Long.class, userId);
        String msgId = "msg-adult-" + SEQ.incrementAndGet();

        JsonNode data = 提交入站(openid, msgId, "周六带爸去复查，提前挂号");
        assertEquals(true, data.get("accepted").asBoolean());

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status, skip_reason, family_id, user_id, content FROM wechat_inbound_message WHERE msg_id = ?",
            msgId);
        assertEquals("PENDING", row.get("status"));
        assertNull(row.get("skip_reason"));
        assertEquals(familyId, ((Number) row.get("family_id")).longValue());
        assertEquals(userId, ((Number) row.get("user_id")).longValue());
        assertEquals("周六带爸去复查，提前挂号", row.get("content"));
    }

    @Test
    @DisplayName("①b 唯一 OWNER 家庭同样受理（OWNER ∈ {OWNER, ADULT}）")
    void singleOwnerFamily_acceptedAsPending() throws Exception {
        String openid = "o-intake-owner-" + SEQ.incrementAndGet();
        long userId = registerAndBind(openid);

        JsonNode data = 提交入站(openid, "msg-owner-" + SEQ.incrementAndGet(), "给孙子买课本");
        assertEquals(true, data.get("accepted").asBoolean());
        assertEquals("PENDING", jdbc.queryForObject(
            "SELECT status FROM wechat_inbound_message WHERE msg_id = ?",
            String.class, 查询最近msgId("msg-owner-")));
    }

    private String 查询最近msgId(String prefix) {
        return jdbc.queryForObject(
            "SELECT msg_id FROM wechat_inbound_message WHERE msg_id LIKE ? ORDER BY id DESC LIMIT 1",
            String.class, prefix + "%");
    }

    @Test
    @DisplayName("② 未绑定 openid → accepted=false, reason=not_bound，落 SKIPPED 留痕")
    void unboundOpenid_skippedNotBound() throws Exception {
        String msgId = "msg-notbound-" + SEQ.incrementAndGet();
        JsonNode data = 提交入站("o-never-bound-" + SEQ.incrementAndGet(), msgId, "随便说一句");

        assertEquals(false, data.get("accepted").asBoolean());
        assertEquals("not_bound", data.get("reason").asText());

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status, skip_reason, family_id, user_id FROM wechat_inbound_message WHERE msg_id = ?",
            msgId);
        assertEquals("SKIPPED", row.get("status"));
        assertEquals("not_bound", row.get("skip_reason"));
        assertNull(row.get("family_id"));
        assertNull(row.get("user_id"));
    }

    @Test
    @DisplayName("③ 已绑定但无活跃家庭 → reason=no_family")
    void boundUserWithoutFamily_skippedNoFamily() throws Exception {
        String openid = "o-intake-nofamily-" + SEQ.incrementAndGet();
        long userId = registerAndBind(openid);
        List<FamilyMember> members = familyMemberRepository.findAllByUserIdAndLeftAtIsNull(userId);
        members.forEach(m -> {
            m.setLeftAt(Instant.now());
            familyMemberRepository.save(m);
        });

        JsonNode data = 提交入站(openid, "msg-nofamily-" + SEQ.incrementAndGet(), "下周去体检");
        assertEquals(false, data.get("accepted").asBoolean());
        assertEquals("no_family", data.get("reason").asText());
        assertEquals("SKIPPED", jdbc.queryForObject(
            "SELECT status FROM wechat_inbound_message WHERE openid = ?", String.class, openid));
    }

    @Test
    @DisplayName("④ 唯一家庭 role=CHILD → reason=role_denied（省掉 agent 侧必然的 40302）")
    void childRole_skippedRoleDenied() throws Exception {
        String openid = "o-intake-child-" + SEQ.incrementAndGet();
        long userId = registerAndBind(openid);
        改角色(userId, FamilyRole.CHILD);

        JsonNode data = 提交入站(openid, "msg-child-" + SEQ.incrementAndGet(), "帮我记一下明天交作业");
        assertEquals(false, data.get("accepted").asBoolean());
        assertEquals("role_denied", data.get("reason").asText());
        assertEquals("role_denied", jdbc.queryForObject(
            "SELECT skip_reason FROM wechat_inbound_message WHERE openid = ?", String.class, openid));
    }

    @Test
    @DisplayName("⑤ 两个活跃家庭 → reason=multi_family，不猜家庭（硬约束 4）")
    void multiFamily_skippedWithoutGuessing() throws Exception {
        String openid = "o-intake-multi-" + SEQ.incrementAndGet();
        long userId = registerAndBind(openid);
        CreateFamilyRequest req = new CreateFamilyRequest();
        req.setName("第二家庭");
        familyService.createFamily(req, userId);

        JsonNode data = 提交入站(openid, "msg-multi-" + SEQ.incrementAndGet(), "周末两家都回去看看");
        assertEquals(false, data.get("accepted").asBoolean());
        assertEquals("multi_family", data.get("reason").asText());

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status, family_id FROM wechat_inbound_message WHERE openid = ?", openid);
        assertEquals("SKIPPED", row.get("status"));
        assertNull(row.get("family_id"), "多家庭时不得挑一个家庭写入");
    }

    @Test
    @DisplayName("⑥ 同 msg_id 重推两次 → 第二次 reason=duplicate 且表内仍 1 行")
    void duplicateMsgId_isIdempotent() throws Exception {
        String openid = "o-intake-dup-" + SEQ.incrementAndGet();
        long userId = registerAndBind(openid);
        改角色(userId, FamilyRole.ADULT);
        String msgId = "msg-dup-" + SEQ.incrementAndGet();

        JsonNode first = 提交入站(openid, msgId, "周五取中药");
        assertEquals(true, first.get("accepted").asBoolean());

        JsonNode second = 提交入站(openid, msgId, "周五取中药");
        assertEquals(false, second.get("accepted").asBoolean());
        assertEquals("duplicate", second.get("reason").asText());

        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM wechat_inbound_message WHERE msg_id = ?", Integer.class, msgId);
        assertEquals(1, rows.intValue());
    }

    @Test
    @DisplayName("⑨ content 超 200 字符 → 截断入库，不触发列宽异常")
    void longContent_isTruncated() throws Exception {
        String openid = "o-intake-long-" + SEQ.incrementAndGet();
        long userId = registerAndBind(openid);
        改角色(userId, FamilyRole.ADULT);
        String msgId = "msg-long-" + SEQ.incrementAndGet();

        JsonNode data = 提交入站(openid, msgId, "啊".repeat(250));
        assertEquals(true, data.get("accepted").asBoolean());

        String stored = jdbc.queryForObject(
            "SELECT content FROM wechat_inbound_message WHERE msg_id = ?", String.class, msgId);
        assertNotNull(stored);
        assertEquals(200, stored.length());
    }

    @Test
    @DisplayName("⑩ 缺幂等键 msg_id → 40099 且不落库（否则重推会撞 UNIQUE 抛 500）")
    void blankMsgId_isRejectedWithoutRow() throws Exception {
        String openid = "o-intake-blankid-" + SEQ.incrementAndGet();
        mvc.perform(post("/api/internal/wechat/inbound-message")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of(
                    "openid", openid,
                    "msg_id", "  ",
                    "content", "周六带爸去复查",
                    "received_at", "2026-09-13T08:00:00Z"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(40099));
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM wechat_inbound_message WHERE openid = ?",
            Integer.class, openid).intValue());
    }
}
