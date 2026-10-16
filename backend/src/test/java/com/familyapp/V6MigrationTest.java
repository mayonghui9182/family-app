package com.familyapp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * V6 迁移脚本验证测试（H2 兼容模式）
 *
 * <p>验证范围：
 * <ol>
 *   <li>用例一：7 张新表全部建表成功（subtasks/notes/rule_templates/rule_template_versions/
 *       task_schedules/task_triggers/summary_execution_log）</li>
 *   <li>用例二：演示家庭 family_id=1 的 3 条种子模板已插入
 *       （周末大扫除分解 / 上学日检查书包日常 / 月度记账日常）</li>
 * </ol>
 *
 * <p>H2 兼容说明：
 * <ul>
 *   <li>H2 不支持 Flyway 跑 V1-V5（partial index WHERE 语法不兼容），因此测试用 ddl-auto=create-drop
 *       让 Hibernate 从 JPA 实体建现有表，再用 @Sql 直接跑 V6.sql 建 7 张新表 + 种子。</li>
 *   <li>V6.sql 的 PostgreSQL 专有段（agent_reader DO 块 + 列级 GRANT + partial unique index WHERE）
 *       通过 Flyway afterMigrate 回调在生产环境执行，H2 测试不涉及。</li>
 *   <li>JSONB 在 H2 MODE=PostgreSQL 下原生支持（实测通过），V6.sql 直接使用 JSONB 类型。</li>
 *   <li>加 @Transactional 保证每个用例的事务自动回滚，@Sql 在干净状态下重复执行不撞唯一约束。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
@Sql(scripts = {
    // 1. 预置演示家庭种子数据（family_id=1 + user_id=1 + 4 分类），供 V6 FK 与种子模板引用
    "classpath:db/v6-test-setup.sql",
    // 2. 运行 V6 迁移脚本（7 表 DDL + tasks.status CHECK + 3 条种子模板）
    //    V6.sql 不存在时此步失败 = TDD RED 阶段；存在且通过 = GREEN 阶段
    "classpath:db/migration/V6__Add_Agent_AI_Task_Decomposition.sql"
})
class V6MigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 验证7张新表全部建表成功() {
        // 查询 INFORMATION_SCHEMA.TABLES，LOWER 兼容 H2（大写）与 PostgreSQL（小写）
        Integer tableCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES " +
            "WHERE LOWER(TABLE_NAME) IN (" +
            "  'subtasks','notes','rule_templates','rule_template_versions'," +
            "  'task_schedules','task_triggers','summary_execution_log')",
            Integer.class);
        assertEquals(7, tableCount, "V6 迁移应创建 7 张新表");
    }

    @Test
    void 验证演示家庭三条种子模板已插入() {
        // v6-test-setup.sql 预置演示家庭 family_id=1（H2 ddl-auto=create-drop 跳过 V1-V5 迁移）
        // V6 种子模板应插入到该演示家庭下，预期 3 条 rule_templates
        Integer seedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM rule_templates WHERE family_id = 1",
            Integer.class);
        assertEquals(3, seedCount, "演示家庭 family_id=1 应有 3 条种子模板");
    }
}
