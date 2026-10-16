package com.familyapp.calendar;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Change 13 日程→任务幂等真实路径测试（补测试欠债）。
 *
 * <p>背景：
 * <ul>
 *   <li>幂等兜底索引 {@code uq_tasks_event_date} 定义在
 *       {@code afterMigrate__agent_reader.sql}，是 PG 专有 partial unique index
 *       （{@code WHERE source_event_id IS NOT NULL AND deleted_at IS NULL}），H2 不支持 partial 语法。</li>
 *   <li>现有 {@link CalendarSchedulerJobsTest} 用例 9 仅 mock {@code taskRepository.save} 抛
 *       {@code DuplicateKeyException}，只验证 catch 吞异常，没验证 DB 层索引真能挡重复。</li>
 * </ul>
 *
 * <p>本测试补两块缺口：
 * <ol>
 *   <li>用例 1：在 H2 上建**非 partial 等效唯一索引**，JdbcTemplate 真实 insert 两条同
 *       {@code (source_event_id, due_date)} 任务，断言第二条撞 {@code DuplicateKeyException}——
 *       验证「唯一索引在 DB 层挡重复 insert」的运行时行为。</li>
 *   <li>用例 2：静态读取 afterMigrate 脚本，断言 {@code uq_tasks_event_date} 的 DDL 包含
 *       正确的 partial 条件——验证「生产 PG 上索引会带 WHERE 条件」（H2 跑不了 partial，靠静态检查兜底）。</li>
 * </ol>
 *
 * <p>与用例 9（mock catch 吞异常）结合，覆盖完整链路：DB 挡重复 → 抛 DataAccessException → catch 吞掉。
 *
 * <p>⚠️ 设计注意：本类**不使用 @Transactional**，因为 H2 中 DDL（CREATE/DROP INDEX）会隐式提交事务，
 * 导致 @Sql 种子数据与测试 DML 不被回滚、污染其他测试类。索引与测试数据由本类自行建/清理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class CalendarTaskIdempotencyTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 每个用例前建非 partial 等效唯一索引（H2 不支持 WHERE 子句索引）。
     * 生产 PG 的 partial 版本由 afterMigrate 回调创建，此处验证等效唯一性语义。
     */
    @BeforeEach
    void setUpUniqueIndex() {
        jdbcTemplate.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS uq_tasks_event_date " +
            "ON tasks (source_event_id, due_date)");
        // 清理本测试可能残留的测试数据（source_event_id 取大值避免与其他测试冲突）
        jdbcTemplate.update("DELETE FROM tasks WHERE source_event_id IN (998, 999)");
    }

    /** 每个用例后清理：删测试数据 + 删索引，避免污染其他测试类。 */
    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tasks WHERE source_event_id IN (998, 999)");
        jdbcTemplate.execute("DROP INDEX IF EXISTS uq_tasks_event_date");
    }

    /** 确保 family_id=1 存在（tasks.family_id 有 FK 约束），幂等。 */
    private void ensureFamilyOne() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM families WHERE id = 1", Integer.class);
        if (count != null && count == 0) {
            jdbcTemplate.update(
                "INSERT INTO families (id, name, created_by, created_at, updated_at) " +
                "VALUES (1, '幂等测试家', NULL, NOW(), NOW())");
        }
    }

    /**
     * 用例 1：DB 层唯一索引挡重复 insert。
     *
     * <p>第一条插入成功；第二条 {@code (source_event_id, due_date)} 完全相同的插入应被唯一索引拒绝，
     * Spring 把 SQL 异常包装成 {@link DuplicateKeyException}（{@code DataAccessException} 子类），
     * 正是 {@link CalendarSchedulerJobs#executeForFamily} catch 块捕获的异常类型。
     */
    @Test
    void duplicate_source_event_and_due_date_is_rejected_by_unique_index() {
        ensureFamilyOne();

        // 第一条：成功
        int first = jdbcTemplate.update(
            "INSERT INTO tasks (title, status, priority, source, family_id, " +
            "created_at, updated_at, source_event_id, due_date) " +
            "VALUES ('日程生成任务-1', 'PENDING', 'MEDIUM', 'CALENDAR', 1, " +
            "NOW(), NOW(), 999, '2026-09-11')");
        assertThat(first).isEqualTo(1);

        // 第二条：同 (source_event_id, due_date) 应撞唯一索引
        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO tasks (title, status, priority, source, family_id, " +
            "created_at, updated_at, source_event_id, due_date) " +
            "VALUES ('日程生成任务-2', 'PENDING', 'MEDIUM', 'CALENDAR', 1, " +
            "NOW(), NOW(), 999, '2026-09-11')"))
            .isInstanceOf(DuplicateKeyException.class)
            .satisfies(error -> assertThat(error.getMessage().toLowerCase(java.util.Locale.ROOT))
                .contains("uq_tasks_event_date"));
    }

    /**
     * 用例 1b：不同 due_date 不撞索引（幂等只在「同日同事程」维度生效）。
     * 验证索引键是 {@code (source_event_id, due_date)} 而非仅 {@code source_event_id}。
     */
    @Test
    void same_event_different_due_date_does_not_conflict() {
        ensureFamilyOne();

        jdbcTemplate.update(
            "INSERT INTO tasks (title, status, priority, source, family_id, " +
            "created_at, updated_at, source_event_id, due_date) " +
            "VALUES ('9-11 任务', 'PENDING', 'MEDIUM', 'CALENDAR', 1, " +
            "NOW(), NOW(), 998, '2026-09-11')");

        // 同一 source_event_id，不同 due_date → 不应撞索引
        int second = jdbcTemplate.update(
            "INSERT INTO tasks (title, status, priority, source, family_id, " +
            "created_at, updated_at, source_event_id, due_date) " +
            "VALUES ('9-12 任务', 'PENDING', 'MEDIUM', 'CALENDAR', 1, " +
            "NOW(), NOW(), 998, '2026-09-12')");
        assertThat(second).isEqualTo(1);
    }

    /**
     * 用例 2：静态验证 afterMigrate 脚本中 uq_tasks_event_date 的 partial unique DDL 正确。
     *
     * <p>H2 无法执行 partial index（{@code CREATE UNIQUE INDEX ... WHERE ...}），故用静态断言兜底，
     * 防止 DDL 被误改（比如丢 WHERE 条件、列名拼错）。
     */
    @Test
    void after_migrate_script_contains_partial_unique_index_ddl() throws IOException {
        String ddl = new String(
            new ClassPathResource("db/migration/afterMigrate__agent_reader.sql")
                .getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);

        // 索引名 + 表 + 列
        assertThat(ddl).contains("CREATE UNIQUE INDEX IF NOT EXISTS uq_tasks_event_date");
        assertThat(ddl).contains("ON tasks (source_event_id, due_date)");
        // partial 条件：只对有来源日程且未删除的任务去重
        assertThat(ddl).contains("WHERE source_event_id IS NOT NULL AND deleted_at IS NULL");
    }
}
