package com.familyapp.wechat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V15 的 PostgreSQL 真实方言集成测试。
 *
 * <p>默认测试套件不依赖 Docker；只有显式提供隔离测试库连接时才运行。
 * 为避免误清理业务库，数据库名必须以 familyapp_wechat_it_ 开头。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "FAMILYAPP_POSTGRES_TEST_URL", matches = ".+")
class WechatConversationPostgresIntegrationTest {

    private String url;
    private String username;
    private String password;

    @BeforeAll
    void setUp() {
        url = System.getenv("FAMILYAPP_POSTGRES_TEST_URL");
        username = requiredEnvironment("FAMILYAPP_POSTGRES_TEST_USER");
        password = requiredEnvironment("FAMILYAPP_POSTGRES_TEST_PASSWORD");
        assertIsolatedDatabase(url);
        cleanDatabase();
    }

    @AfterAll
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void v15PreservesOldDraftReadsAndEnforcesPostgresConstraints() throws Exception {
        migrateTo("14");
        long draftId = seedOldIntakeAndDraft();

        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE wechat_chat_context (id BIGINT PRIMARY KEY)");
        }

        assertThrows(FlywayException.class, () -> latestFlyway().migrate(),
                "预置同名表应让 V15 原子失败");
        assertOldReadPath(draftId);

        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE wechat_chat_context");
        }

        assertEquals(2, latestFlyway().migrate().migrationsExecuted,
                "移除冲突后应依次执行 V15 和 V16");
        assertOldReadPath(draftId);

        verifyJsonbAndCompositeFamilyConstraint();
        verifyCascadeAndSetNull();
        verifyConcurrentActiveSlot();
    }

    private long seedOldIntakeAndDraft() throws SQLException {
        try (Connection connection = connect()) {
            long draftId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO agent_decompose_sessions(
                        family_id, session_type, status, init_task_title,
                        messages, created_by
                    ) VALUES (
                        1, 'TASK_CREATE', 'ACTIVE', '旧 H5 草稿',
                        '[]'::jsonb, 1
                    )
                    RETURNING id
                    """);
                 ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                draftId = resultSet.getLong(1);
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO wechat_inbound_message(
                        msg_id, openid, content, status, attempts,
                        session_id, family_id, user_id
                    ) VALUES (
                        'pg-v15-old-message', 'pg-v15-old-openid', '旧消息原文',
                        'DONE', 1, ?, 1, 1
                    )
                    """)) {
                statement.setLong(1, draftId);
                statement.executeUpdate();
            }
            return draftId;
        }
    }

    private void assertOldReadPath(long draftId) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT status, session_type, init_task_title, family_id, created_by,
                            jsonb_typeof(messages) AS messages_type
                       FROM agent_decompose_sessions
                      WHERE id = ? AND family_id = 1
                     """)) {
            statement.setLong(1, draftId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "失败迁移后旧 H5 草稿必须仍可读取");
                assertEquals("ACTIVE", resultSet.getString("status"));
                assertEquals("TASK_CREATE", resultSet.getString("session_type"));
                assertEquals("旧 H5 草稿", resultSet.getString("init_task_title"));
                assertEquals(1L, resultSet.getLong("family_id"));
                assertEquals(1L, resultSet.getLong("created_by"));
                assertEquals("array", resultSet.getString("messages_type"));
            }
        }

        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT status, session_id, content
                       FROM wechat_inbound_message
                      WHERE msg_id = 'pg-v15-old-message'
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next(), "失败迁移后旧入站消息必须仍可读取");
            assertEquals("DONE", resultSet.getString("status"));
            assertEquals(draftId, resultSet.getLong("session_id"));
            assertEquals("旧消息原文", resultSet.getString("content"));
        }
    }

    private void verifyJsonbAndCompositeFamilyConstraint() throws SQLException {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO families(id, name, created_by)
                    VALUES (101, 'JSON 家庭', 1), (103, '边界家庭 A', 1), (104, '边界家庭 B', 1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO wechat_bind(id, openid, user_id)
                    VALUES (1001, 'pg-v15-json', 1), (1003, 'pg-v15-boundary', 1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO wechat_task_conversations(
                        id, family_id, wechat_bind_id, user_id, status,
                        active_slot, slot_state, expires_at
                    ) VALUES (
                        2001, 101, 1001, 1, 'ACTIVE',
                        1, '{"topic":"买菜","count":2}'::jsonb, NOW() + INTERVAL '1 day'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO wechat_conversation_messages(
                        wechat_account_id, external_msg_id, openid, direction, content,
                        wechat_bind_id, user_id, processing_status, received_at
                    ) VALUES (
                        'pg-official', 'pg-waiting-family', 'pg-v15-json',
                        'INBOUND', '等待家庭选择', 1001, 1, 'WAITING_FAMILY', NOW()
                    )
                    """);
            assertEquals(1, scalarInt(statement, """
                    SELECT COUNT(*)
                      FROM wechat_conversation_messages
                     WHERE processing_status = 'WAITING_FAMILY'
                    """));

            try (ResultSet resultSet = statement.executeQuery("""
                    SELECT slot_state ->> 'topic' AS topic,
                           (slot_state ->> 'count')::int AS item_count,
                           pg_typeof(slot_state)::text AS column_type
                      FROM wechat_task_conversations
                     WHERE id = 2001
                    """)) {
                assertTrue(resultSet.next());
                assertEquals("买菜", resultSet.getString("topic"));
                assertEquals(2, resultSet.getInt("item_count"));
                assertEquals("jsonb", resultSet.getString("column_type"));
            }

            statement.executeUpdate("""
                    INSERT INTO wechat_task_conversations(
                        id, family_id, wechat_bind_id, user_id, status,
                        active_slot, slot_state, expires_at
                    ) VALUES (
                        2003, 103, 1003, 1, 'ACTIVE',
                        1, '{}'::jsonb, NOW() + INTERVAL '1 day'
                    )
                    """);

            SQLException crossFamily = assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO wechat_conversation_messages(
                        wechat_account_id, external_msg_id, openid, direction, content,
                        family_id, conversation_id, received_at
                    ) VALUES (
                        'pg-official', 'pg-cross-family', 'pg-openid-boundary', 'INBOUND', '越权消息',
                        104, 2003, NOW()
                    )
                    """));
            assertEquals("23503", crossFamily.getSQLState(),
                    "跨家庭引用必须由 PostgreSQL 复合外键拒绝");
        }
    }

    private void verifyCascadeAndSetNull() throws SQLException {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO families(id, name, created_by)
                    VALUES (102, '级联家庭', 1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO wechat_bind(id, openid, user_id)
                    VALUES (1002, 'pg-v15-cascade', 1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO wechat_chat_context(
                        id, wechat_bind_id, user_id, current_family_id
                    ) VALUES (3002, 1002, 1, 102)
                    """);
            statement.executeUpdate("""
                    INSERT INTO wechat_task_conversations(
                        id, family_id, wechat_bind_id, user_id, status,
                        active_slot, slot_state, expires_at
                    ) VALUES (
                        2002, 102, 1002, 1, 'ACTIVE',
                        1, '{}'::jsonb, NOW() + INTERVAL '1 day'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO wechat_conversation_messages(
                        id, wechat_account_id, openid, direction, content,
                        family_id, conversation_id, received_at
                    ) VALUES (
                        4002, 'pg-official', 'pg-openid-cascade', 'OUTBOUND', '待发送',
                        102, 2002, NOW()
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO wechat_message_outbox(
                        family_id, conversation_id, message_id,
                        dedupe_key, sequence_no
                    ) VALUES (
                        102, 2002, 4002, 'pg-cascade', 1
                    )
                    """);

            statement.executeUpdate("DELETE FROM families WHERE id = 102");

            assertEquals(0, scalarInt(statement,
                    "SELECT COUNT(*) FROM wechat_task_conversations WHERE id = 2002"));
            assertEquals(0, scalarInt(statement,
                    "SELECT COUNT(*) FROM wechat_conversation_messages WHERE id = 4002"));
            assertEquals(0, scalarInt(statement,
                    "SELECT COUNT(*) FROM wechat_message_outbox WHERE message_id = 4002"));

            try (ResultSet resultSet = statement.executeQuery("""
                    SELECT current_family_id
                      FROM wechat_chat_context
                     WHERE id = 3002
                    """)) {
                assertTrue(resultSet.next(), "路由上下文应随绑定保留");
                assertNull(resultSet.getObject("current_family_id"),
                        "家庭删除后 current_family_id 应 SET NULL");
            }
        }
    }

    private void verifyConcurrentActiveSlot() throws Exception {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO families(id, name, created_by)
                    VALUES (105, '并发家庭', 1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO wechat_bind(id, openid, user_id)
                    VALUES (1005, 'pg-v15-concurrency', 1)
                    """);
        }

        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<ActiveSlotResult> insert = () -> insertActiveConversation(start);
            List<Future<ActiveSlotResult>> futures = pool.invokeAll(List.of(insert, insert));
            List<ActiveSlotResult> results = futures.stream()
                    .map(this::getFuture)
                    .toList();

            assertEquals(1, results.stream().filter(result -> result == ActiveSlotResult.CREATED).count());
            assertEquals(1, results.stream().filter(result -> result == ActiveSlotResult.CONFLICT).count());
        } finally {
            pool.shutdownNow();
        }

        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            assertEquals(1, scalarInt(statement, """
                    SELECT COUNT(*)
                      FROM wechat_task_conversations
                     WHERE family_id = 105
                       AND wechat_bind_id = 1005
                       AND active_slot = 1
                    """));
        }
    }

    private ActiveSlotResult insertActiveConversation(CyclicBarrier start) throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            start.await();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO wechat_task_conversations(
                        family_id, wechat_bind_id, user_id, status,
                        active_slot, slot_state, expires_at
                    ) VALUES (
                        105, 1005, 1, 'ACTIVE',
                        1, '{}'::jsonb, NOW() + INTERVAL '1 day'
                    )
                    """)) {
                statement.executeUpdate();
                connection.commit();
                return ActiveSlotResult.CREATED;
            } catch (SQLException exception) {
                connection.rollback();
                if ("23505".equals(exception.getSQLState())) {
                    return ActiveSlotResult.CONFLICT;
                }
                throw exception;
            }
        }
    }

    private ActiveSlotResult getFuture(Future<ActiveSlotResult> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new IllegalStateException("并发 active_slot 验证失败", exception);
        }
    }

    private int scalarInt(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .target(target)
                .cleanDisabled(false)
                .load()
                .migrate();
    }

    private Flyway latestFlyway() {
        return Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
    }

    private void cleanDatabase() {
        latestFlyway().clean();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertNotNull(value, name + " 未配置");
        assertTrue(!value.isBlank(), name + " 不能为空");
        return value;
    }

    private void assertIsolatedDatabase(String jdbcUrl) {
        assertNotNull(jdbcUrl, "FAMILYAPP_POSTGRES_TEST_URL 未配置");
        int queryStart = jdbcUrl.indexOf('?');
        String withoutQuery = queryStart >= 0 ? jdbcUrl.substring(0, queryStart) : jdbcUrl;
        String databaseName = withoutQuery.substring(withoutQuery.lastIndexOf('/') + 1);
        assertTrue(databaseName.startsWith("familyapp_wechat_it_"),
                "拒绝清理非隔离测试库：" + databaseName);
    }

    private enum ActiveSlotResult {
        CREATED,
        CONFLICT
    }
}
