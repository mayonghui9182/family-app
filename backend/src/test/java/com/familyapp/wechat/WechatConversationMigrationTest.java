package com.familyapp.wechat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WechatConversationMigrationTest {

    private static final String MIGRATION_NAME = "V15__Add_Wechat_Task_Conversations.sql";

    private static final List<String> NEW_TABLES = List.of(
            "wechat_chat_context",
            "wechat_task_conversations",
            "wechat_conversation_messages",
            "wechat_candidate_versions",
            "wechat_task_confirmations",
            "wechat_confirmation_tasks",
            "wechat_message_outbox",
            "family_task_experiences",
            "family_task_experience_evidence"
    );

    @Test
    void v15MigratesOnH2AndEnforcesCoreTenantConstraints(@TempDir Path migrationDirectory) throws Exception {
        String databaseName = "wechat_v15_" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:h2:mem:" + databaseName
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";

        copyMigrationTo(migrationDirectory);
        createV14BaselineSchema(url);

        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("filesystem:" + migrationDirectory.toAbsolutePath())
                .baselineOnMigrate(true)
                .baselineVersion("14")
                .load();

        assertEquals(1, flyway.migrate().migrationsExecuted);

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            for (String table : NEW_TABLES) {
                assertTrue(tableExists(connection, table), () -> "缺少 V15 新表：" + table);
            }
            verifyCoreConstraints(connection);
        }
    }

    /**
     * 复制实际迁移资源，既避免 SQL 副本漂移，也隔离仅支持 PostgreSQL 的 afterMigrate 回调。
     */
    private void copyMigrationTo(Path migrationDirectory) throws IOException {
        try (InputStream source = getClass().getClassLoader()
                .getResourceAsStream("db/migration/" + MIGRATION_NAME)) {
            assertNotNull(source, "找不到 V15 迁移资源");
            Files.copy(source, migrationDirectory.resolve(MIGRATION_NAME), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test
    void v16DefaultPreferenceMigratesAndFamilyDeleteClearsIt(@TempDir Path directory) throws Exception {
        String url = "jdbc:h2:mem:default_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        createV14BaselineSchema(url);
        copyMigrationTo(directory);
        String migration = "V16__Wechat_Default_Family.sql";
        try (InputStream source = getClass().getClassLoader().getResourceAsStream("db/migration/" + migration)) {
            assertNotNull(source);
            Files.copy(source, directory.resolve(migration));
        }
        var flyway = Flyway.configure().dataSource(url, "sa", "").locations("filesystem:" + directory)
                .baselineOnMigrate(true).baselineVersion("14").load();
        assertEquals(2, flyway.migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO users(id) VALUES(1)");
            statement.execute("INSERT INTO families(id) VALUES(10)");
            statement.execute("INSERT INTO wechat_bind(id, openid, user_id) VALUES(100, 'openid-default', 1)");
            statement.execute("INSERT INTO wechat_chat_context(wechat_bind_id, user_id, default_family_id) VALUES(100, 1, 10)");
            assertThrows(SQLException.class, () -> statement.execute("UPDATE wechat_chat_context SET default_family_id=999"));
            statement.execute("DELETE FROM families WHERE id=10");
            try (var result = statement.executeQuery("SELECT default_family_id FROM wechat_chat_context WHERE wechat_bind_id=100")) {
                assertTrue(result.next());
                org.junit.jupiter.api.Assertions.assertNull(result.getObject(1));
            }
            // 无生产偏好的隔离测试库验证可恢复的列级逆向操作，不回滚已承载业务的 V15 表。
            statement.execute("ALTER TABLE wechat_chat_context DROP COLUMN default_family_id");
            try (var result = statement.executeQuery("SELECT user_id FROM wechat_chat_context WHERE wechat_bind_id=100")) {
                assertTrue(result.next());
                assertEquals(1L, result.getLong(1));
            }
        }
    }

    /**
     * 测试只验证 V15 本身：用最小 V14 基线隔离早期历史迁移的 H2 方言差异。
     */
    private void createV14BaselineSchema(String url) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE users (
                        id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY
                    )
                    """);
            statement.execute("""
                    CREATE TABLE families (
                        id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY
                    )
                    """);
            statement.execute("""
                    CREATE TABLE tasks (
                        id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        family_id BIGINT NOT NULL REFERENCES families(id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE wechat_bind (
                        id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        openid VARCHAR(64) NOT NULL UNIQUE,
                        user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE
                    )
                    """);
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                  FROM information_schema.tables
                 WHERE LOWER(table_schema) = 'public'
                   AND LOWER(table_name) = ?
                """)) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) == 1;
            }
        }
    }

    private void verifyCoreConstraints(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users(id) VALUES (1)");
            statement.executeUpdate("INSERT INTO families(id) VALUES (10), (20)");
            statement.executeUpdate("INSERT INTO tasks(id, family_id) VALUES (100, 10)");
            statement.executeUpdate("INSERT INTO wechat_bind(id, openid, user_id) VALUES (1000, 'openid-1', 1)");

            statement.executeUpdate("""
                    INSERT INTO wechat_task_conversations(
                        id, family_id, wechat_bind_id, user_id, status, active_slot,
                        current_revision, slot_state, expires_at
                    ) VALUES (
                        2000, 10, 1000, 1, 'ACTIVE', 1,
                        0, '{}', CURRENT_TIMESTAMP + INTERVAL '1' DAY
                    )
                    """);

            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO wechat_task_conversations(
                        id, family_id, wechat_bind_id, user_id, status, active_slot,
                        current_revision, slot_state, expires_at
                    ) VALUES (
                        2001, 10, 1000, 1, 'ACTIVE', 1,
                        0, '{}', CURRENT_TIMESTAMP + INTERVAL '1' DAY
                    )
                    """));

            statement.executeUpdate("""
                    INSERT INTO wechat_task_conversations(
                        id, family_id, wechat_bind_id, user_id, status, active_slot,
                        current_revision, slot_state, expires_at
                    ) VALUES (
                        2002, 10, 1000, 1, 'CONFIRMED', NULL,
                        1, '{}', CURRENT_TIMESTAMP + INTERVAL '1' DAY
                    )
                    """);

            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO wechat_conversation_messages(
                        id, wechat_account_id, external_msg_id, direction, content, received_at
                    ) VALUES (
                        2999, 'official-1', 'msg-without-openid',
                        'INBOUND', '缺少 openid', CURRENT_TIMESTAMP
                    )
                    """));

            statement.executeUpdate("""
                    INSERT INTO wechat_conversation_messages(
                        id, wechat_account_id, external_msg_id, openid, direction, content,
                        wechat_bind_id, user_id, family_id, conversation_id, received_at
                    ) VALUES (
                        3000, 'official-1', 'msg-1', 'openid-1', 'INBOUND', '完整原文',
                        1000, 1, 10, 2000, CURRENT_TIMESTAMP
                    )
                    """);

            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO wechat_conversation_messages(
                        id, wechat_account_id, external_msg_id, openid, direction, content, received_at
                    ) VALUES (
                        3001, 'official-1', 'msg-1', 'openid-1', 'INBOUND', '重复消息', CURRENT_TIMESTAMP
                    )
                    """));

            statement.executeUpdate("""
                    INSERT INTO wechat_conversation_messages(
                        id, wechat_account_id, external_msg_id, openid, direction, content, received_at
                    ) VALUES (
                        3002, 'official-2', 'msg-1', 'openid-2', 'INBOUND', '另一公众号', CURRENT_TIMESTAMP
                    )
                    """);

            statement.executeUpdate("""
                    INSERT INTO wechat_conversation_messages(
                        id, wechat_account_id, external_msg_id, openid, direction, content,
                        wechat_bind_id, user_id, processing_status, received_at
                    ) VALUES (
                        3004, 'official-1', 'msg-waiting-family', 'openid-1',
                        'INBOUND', '等待选择家庭', 1000, 1, 'WAITING_FAMILY', CURRENT_TIMESTAMP
                    )
                    """);

            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO wechat_conversation_messages(
                        id, wechat_account_id, external_msg_id, openid, direction, content,
                        wechat_bind_id, user_id, family_id, conversation_id, received_at
                    ) VALUES (
                        3003, 'official-1', 'msg-cross-family', 'openid-1', 'INBOUND', '跨家庭消息',
                        1000, 1, 20, 2000, CURRENT_TIMESTAMP
                    )
                    """));

            statement.executeUpdate("""
                    INSERT INTO wechat_candidate_versions(
                        id, conversation_id, family_id, revision, candidate_payload,
                        rendered_preview, source_message_id, model_name, prompt_version
                    ) VALUES (
                        4000, 2000, 10, 1, '[]',
                        '1. 买菜', 3000, 'test-model', 'test-v1'
                    )
                    """);

            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO wechat_task_confirmations(
                        id, conversation_id, family_id, candidate_version_id,
                        candidate_revision, candidate_hash, confirmation_message_id,
                        confirmed_by, idempotency_key
                    ) VALUES (
                        5001, 2000, 20, 4000,
                        1, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        3000, 1, 'cross-family-confirmation'
                    )
                    """));

            statement.executeUpdate("""
                    INSERT INTO wechat_task_confirmations(
                        id, conversation_id, family_id, candidate_version_id,
                        candidate_revision, candidate_hash, confirmation_message_id,
                        confirmed_by, idempotency_key
                    ) VALUES (
                        5000, 2000, 10, 4000,
                        1, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        3000, 1, 'valid-confirmation'
                    )
                    """);

            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO wechat_confirmation_tasks(
                        id, family_id, confirmation_id, task_id,
                        candidate_item_index, initial_task_snapshot
                    ) VALUES (
                        6000, 20, 5000, 100,
                        0, '{}'
                    )
                    """));

            statement.executeUpdate("""
                    INSERT INTO wechat_conversation_messages(
                        id, wechat_account_id, openid, direction, content,
                        family_id, conversation_id, received_at
                    ) VALUES (
                        3100, 'official-1', 'openid-1', 'OUTBOUND', '候选任务',
                        10, 2000, CURRENT_TIMESTAMP
                    )
                    """);

            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO wechat_message_outbox(
                        id, family_id, conversation_id, message_id,
                        dedupe_key, sequence_no
                    ) VALUES (
                        7000, 20, 2000, 3100,
                        'cross-family-outbox', 1
                    )
                    """));


            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO family_task_experiences(
                        id, family_id, normalized_key, value_payload, scope_payload,
                        status, confidence, evidence_count, version, activation_source
                    ) VALUES (
                        8001, 10, 'invalid-version', '{}', '{}',
                        'CANDIDATE', 0.5, 0, 0, 'TASK_EVIDENCE'
                    )
                    """));

            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO family_task_experiences(
                        id, family_id, normalized_key, value_payload, scope_payload,
                        status, confidence, evidence_count, version, activation_source
                    ) VALUES (
                        8002, 10, 'invalid-status', '{}', '{}',
                        'BROKEN', 0.5, 0, 1, 'TASK_EVIDENCE'
                    )
                    """));

            statement.executeUpdate("""
                    INSERT INTO family_task_experiences(
                        id, family_id, normalized_key, value_payload, scope_payload,
                        status, confidence, evidence_count, version, activation_source
                    ) VALUES (
                        8000, 10, 'assignee:shopping', '{}', '{}',
                        'CANDIDATE', 0.5, 1, 1, 'TASK_EVIDENCE'
                    )
                    """);

            statement.executeUpdate("""
                    INSERT INTO family_task_experience_evidence(
                        id, family_id, experience_id, source_type, source_id,
                        independence_key, content_hash, observed_at
                    ) VALUES (
                        9000, 10, 8000, 'TASK_OBSERVATION', 'task-result:100',
                        'task:100', 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                        CURRENT_TIMESTAMP
                    )
                    """);

            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO family_task_experience_evidence(
                        id, family_id, experience_id, source_type, source_id,
                        independence_key, content_hash, observed_at
                    ) VALUES (
                        9001, 10, 8000, 'TASK_OBSERVATION', 'task-result:100-again',
                        'task:100', 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                        CURRENT_TIMESTAMP
                    )
                    """));

            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO family_task_experience_evidence(
                        id, family_id, experience_id, source_type, source_id,
                        independence_key, content_hash, observed_at
                    ) VALUES (
                        9002, 20, 8000, 'TASK_OBSERVATION', 'task-result:200',
                        'task:200', 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                        CURRENT_TIMESTAMP
                    )
                    """));

        }
    }
}

