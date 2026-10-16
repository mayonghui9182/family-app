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
 * V9 迁移脚本验证测试（H2 兼容模式）
 *
 * <p>验证范围（Change 8 §1 数据模型）：
 * <ol>
 *   <li>用例一：两张新表 + 两个新列全部创建成功
 *       —— person_profiles / agent_decompose_sessions 表
 *       —— tasks.assignee_profile_id 过渡列（双字段过渡策略）
 *       —— rule_templates.profile_types 列（任务建议匹配条件）</li>
 *   <li>用例二：两张新表的 NOT NULL 约束列正确
 *       —— person_profiles: profile_type / name / avatar_emoji / relation / profile_data / created_by
 *       —— agent_decompose_sessions: family_id / session_type / status / messages / created_by</li>
 * </ol>
 *
 * <p>H2 兼容说明：
 * <ul>
 *   <li>沿用 V6 同款模式：H2 ddl-auto=create-drop 让 Hibernate 从 JPA 实体建现有表
 *       （families/users/categories/tasks/rule_templates 等），再用 @Sql 跑 v6-test-setup.sql
 *       预置演示家庭 family_id=1 + user_id=1 + 4 分类，最后跑 V9.sql 建 2 张新表 + 2 个新列。</li>
 *   <li>V9.sql 的 PostgreSQL 专有段（agent_reader afterMigrate GRANT 列授权）
 *       通过 Flyway 回调在生产环境执行，H2 测试不涉及。</li>
 *   <li>person_profiles.profile_data JSONB 在 H2 MODE=PostgreSQL 下原生支持（V6 已实测通过）。</li>
 *   <li>partial unique index WHERE deleted_at IS NULL 子句由 V9.sql 自身保证 H2 兼容
 *       （Step 3 写 V9.sql 时若发现 H2 不支持，再调整为等价 H2 语法）。</li>
 *   <li>加 @Transactional 保证每个用例的事务自动回滚，@Sql 在干净状态下重复执行不撞唯一约束。</li>
 * </ul>
 *
 * <p>TDD 状态：
 * <ul>
 *   <li>RED 阶段（Step 1c）：V9.sql 不存在 → @Sql 加载失败 → 测试 ERROR/FAIL</li>
 *   <li>GREEN 阶段（Step 8）：Step 3 写完 V9.sql + Step 4 补 afterMigrate → 本测试 PASS</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
@Sql(scripts = {
    // 1. 预置演示家庭种子数据（family_id=1 + user_id=1 + 4 分类），供 V9 FK 引用
    //    复用 V6 的 v6-test-setup.sql（V9 同样需要这些基础数据，不重复造轮子）
    "classpath:db/v6-test-setup.sql",
    // 2. 运行 V9 迁移脚本（2 表 DDL + 2 列 ALTER + 索引）
    //    V9.sql 不存在时此步失败 = TDD RED 阶段；存在且通过 = GREEN 阶段
    "classpath:db/migration/V9__Add_Person_Profiles_And_Decompose_Sessions.sql"
})
class V9MigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 验证两张新表和两个新列全部创建成功() {
        // 1. person_profiles 表（家庭人员档案，支持注册用户与虚拟成员）
        Integer ppCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = 'person_profiles'",
            Integer.class);
        assertEquals(1, ppCount, "V9 迁移应创建 person_profiles 表");

        // 2. agent_decompose_sessions 表（AI 拆解会话草稿箱，D-1~D-4 中间态落盘）
        Integer adsCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = 'agent_decompose_sessions'",
            Integer.class);
        assertEquals(1, adsCount, "V9 迁移应创建 agent_decompose_sessions 表");

        // 3. tasks.assignee_profile_id 过渡列（双字段过渡策略：旧 assignee_id 保留 1 大版本）
        Integer taskColCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE LOWER(TABLE_NAME) = 'tasks' AND LOWER(COLUMN_NAME) = 'assignee_profile_id'",
            Integer.class);
        assertEquals(1, taskColCount, "V9 迁移应为 tasks 表添加 assignee_profile_id 列");

        // 4. rule_templates.profile_types 列（任务建议 similar_templates 匹配条件）
        Integer rtColCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE LOWER(TABLE_NAME) = 'rule_templates' AND LOWER(COLUMN_NAME) = 'profile_types'",
            Integer.class);
        assertEquals(1, rtColCount, "V9 迁移应为 rule_templates 表添加 profile_types 列");
    }

    @Test
    void 验证两张新表的NOT_NULL约束列正确() {
        // person_profiles 必填列：profile_type / name / avatar_emoji / relation / profile_data / created_by
        // 这些列在 DDL 中明确 NOT NULL，是档案数据完整性保障
        Integer ppNotNullCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE LOWER(TABLE_NAME) = 'person_profiles' " +
            "AND LOWER(IS_NULLABLE) = 'no' " +
            "AND LOWER(COLUMN_NAME) IN (" +
            "  'profile_type','name','avatar_emoji','relation','profile_data','created_by')",
            Integer.class);
        assertEquals(6, ppNotNullCount, "person_profiles 应有 6 个 NOT NULL 列");

        // agent_decompose_sessions 必填列：family_id / session_type / status / messages / created_by
        // status DEFAULT 'ACTIVE' + messages DEFAULT '[]' 仍为 NOT NULL（保证会话状态可追溯）
        Integer adsNotNullCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE LOWER(TABLE_NAME) = 'agent_decompose_sessions' " +
            "AND LOWER(IS_NULLABLE) = 'no' " +
            "AND LOWER(COLUMN_NAME) IN (" +
            "  'family_id','session_type','status','messages','created_by')",
            Integer.class);
        assertEquals(5, adsNotNullCount, "agent_decompose_sessions 应有 5 个 NOT NULL 列");
    }
}
