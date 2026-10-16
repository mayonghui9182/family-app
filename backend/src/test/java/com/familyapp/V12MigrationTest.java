package com.familyapp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V12 迁移脚本验证测试（H2 兼容模式，Change 14 微信入站消息幂等队列表）。
 *
 * <p>H2 测试环境说明（沿用 V10/V11 已验证的四条打法）：
 * <ul>
 *   <li>ddl-auto=create-drop 下 Hibernate 先按实体建表且不带 CHECK，
 *       V12 的 CREATE TABLE IF NOT EXISTS 幂等跳过、约束不落地；
 *       因此约束类用例先 DROP 再重放脚本，让建表语句真正执行。</li>
 *   <li>UNIQUE 约束走行为断言（H2 2.x INFORMATION_SCHEMA.INDEXES 无 IS_UNIQUE 列）。</li>
 *   <li>裸 insert 须显式补齐 created_at/updated_at（Hibernate 建的表无 DB DEFAULT）。</li>
 *   <li>轮询索引不做 partial：H2 不支持 CREATE INDEX ... WHERE，
 *       且该索引非唯一、partial 只省空间不影响正确性，故用 (status, received_at) 复合索引，
 *       用例6 以静态 DDL 断言钉住列序。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
@Sql(scripts = {
    "classpath:db/v6-test-setup.sql",
    "classpath:db/migration/V12__Add_Wechat_Inbound_Message.sql"
})
class V12MigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 用例1_wechat_inbound_message表创建成功() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = 'wechat_inbound_message'",
            Integer.class);
        assertEquals(1, count);
    }

    @Test
    void 用例2_幂等列与队列列齐备() {
        String[] columns = {"msg_id", "openid", "content", "received_at", "status",
            "attempts", "session_id", "skip_reason", "last_error", "family_id", "user_id"};
        for (String column : columns) {
            Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE LOWER(TABLE_NAME) = 'wechat_inbound_message' AND LOWER(COLUMN_NAME) = ?",
                Integer.class, column);
            assertEquals(1, exists, "V12 应新增列 " + column);
        }
    }

    @Test
    void 用例3_msgId唯一约束吸收微信重推() {
        assertEquals(1, 插入入站消息("M-unique", "PENDING"));
        assertThrows(DataAccessException.class,
            () -> 插入入站消息("M-unique", "PENDING"),
            "重复 msg_id 插入应违反 uq_wim_msg_id 唯一约束");
    }

    @Test
    void 用例4_status检查约束恰好接受五值() {
        重建表使V12约束生效();
        for (String status : new String[]{"PENDING", "RUNNING", "DONE", "FAILED", "SKIPPED"}) {
            assertEquals(1, 插入入站消息("M-" + status, status),
                status + " 应在五值白名单内");
        }
    }

    @Test
    void 用例5_status第六值被拒绝() {
        重建表使V12约束生效();
        assertThrows(DataAccessException.class,
            () -> 插入入站消息("M-illegal", "CANCELED"),
            "第六个状态值应被 chk_wim_status 拒绝（状态机不得随意扩值）");
    }

    @Test
    void 用例6_openid不建外键以保留未绑定痕迹() {
        // 未绑定 openid 也要落行留痕（skip_reason=not_bound），故 family_id/user_id 可为空、
        // openid 不得建外键。此处插入一个既不存在于 wechat_bind、也无家庭无用户的 openid。
        assertEquals(1, 插入入站消息("M-not-bound", "SKIPPED", "o-never-bound"));
    }

    @Test
    void 用例7_重放脚本后默认值生效() {
        重建表使V12约束生效();
        jdbcTemplate.update(
            "INSERT INTO wechat_inbound_message (msg_id, openid, content) VALUES (?, ?, ?)",
            "M-default", "o-default", "周六带爸去复查");
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM wechat_inbound_message WHERE msg_id = 'M-default'", String.class);
        Integer attempts = jdbcTemplate.queryForObject(
            "SELECT attempts FROM wechat_inbound_message WHERE msg_id = 'M-default'", Integer.class);
        assertEquals("PENDING", status, "缺省状态应为 PENDING（入队即待处理）");
        assertEquals(0, attempts, "缺省重试次数应为 0");
    }

    @Test
    void 用例8_轮询索引列序钉死为status先received_at后() throws Exception {
        String ddl = new String(new ClassPathResource("db/migration/V12__Add_Wechat_Inbound_Message.sql")
            .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(ddl).contains("CREATE INDEX IF NOT EXISTS idx_wim_pending");
        assertThat(ddl).contains("ON wechat_inbound_message (status, received_at)");
    }

    /**
     * DROP 掉 Hibernate 实体建出的无约束表，重放 V12 脚本让 CREATE TABLE 真正执行，
     * chk_wim_status 检查约束与各列 DB DEFAULT 真实落地。
     */
    private void 重建表使V12约束生效() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS wechat_inbound_message");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V12__Add_Wechat_Inbound_Message.sql"))
            .execute(Objects.requireNonNull(jdbcTemplate.getDataSource()));
    }

    /** 完整列插入（不重放的用例里 Hibernate 建的表无 DB DEFAULT，须显式补齐） */
    private int 插入入站消息(String msgId, String status) {
        return 插入入站消息(msgId, status, "o-test-" + msgId);
    }

    private int 插入入站消息(String msgId, String status, String openid) {
        return jdbcTemplate.update(
            "INSERT INTO wechat_inbound_message (msg_id, openid, content, received_at, status, "
            + "attempts, created_at, updated_at) VALUES (?, ?, '测试消息', NOW(), ?, 0, NOW(), NOW())",
            msgId, openid, status);
    }
}
