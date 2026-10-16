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
 * V10 迁移脚本验证测试（H2 兼容模式，Change 11）
 *
 * <p>验证范围：wechat_bind 表（openid ↔ user_id 绑定关系）
 * <ol>
 *   <li>用例一：正向迁移——表 + UNIQUE(openid) + idx_wechat_bind_user 全部创建</li>
 *   <li>用例二：回滚策略——DROP TABLE wechat_bind 后表消失，等价于 V10 逆向回滚</li>
 * </ol>
 *
 * <p>H2 策略沿用 V9：ddl-auto=create-drop 由 Hibernate 建基础表，@Sql 跑
 * v6-test-setup.sql 种子 + V10.sql 建表（CREATE TABLE IF NOT EXISTS 幂等）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
@Sql(scripts = {
    "classpath:db/v6-test-setup.sql",
    "classpath:db/migration/V10__Add_Wechat_Bind.sql"
})
class V10MigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 用例1_正向迁移_wechat_bind表与索引全部创建成功() {
        Integer tableCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = 'wechat_bind'",
            Integer.class);
        assertEquals(1, tableCount, "V10 迁移应创建 wechat_bind 表");

        // openid 列 NOT NULL
        Integer openidNotNull = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE LOWER(TABLE_NAME) = 'wechat_bind' AND LOWER(COLUMN_NAME) = 'openid' " +
            "AND LOWER(IS_NULLABLE) = 'no'",
            Integer.class);
        assertEquals(1, openidNotNull, "openid 应为 NOT NULL");

        // UNIQUE(openid)：行为级验证——重复 openid 插入必须被唯一约束拒绝
        // （H2 2.x INFORMATION_SCHEMA.INDEXES 无 IS_UNIQUE 列，用行为断言代替元数据查询）
        jdbcTemplate.update(
            "INSERT INTO wechat_bind (openid, user_id, bound_at) VALUES ('o-unique-check', 1, CURRENT_TIMESTAMP)");
        org.springframework.dao.DataAccessException dup = org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.dao.DataAccessException.class,
            () -> jdbcTemplate.update(
                "INSERT INTO wechat_bind (openid, user_id, bound_at) VALUES ('o-unique-check', 1, CURRENT_TIMESTAMP)"),
            "重复 openid 插入应违反 uq_wechat_bind_openid 唯一约束");
        org.junit.jupiter.api.Assertions.assertNotNull(dup);

        // user_id 索引存在（H2 索引名大写匹配）
        Integer idxCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES " +
            "WHERE LOWER(TABLE_NAME) = 'wechat_bind' AND LOWER(INDEX_NAME) = 'idx_wechat_bind_user'",
            Integer.class);
        assertEquals(1, idxCount, "应创建 idx_wechat_bind_user 索引");

        // user_id 外键：行为级验证——引用不存在的 users.id 必须被 FK 约束拒绝
        // （H2 各版本 INFORMATION_SCHEMA.CROSS_REFERENCES 列名不一致，用行为断言）
        org.springframework.dao.DataAccessException fk = org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.dao.DataAccessException.class,
            () -> jdbcTemplate.update(
                "INSERT INTO wechat_bind (openid, user_id, bound_at) " +
                "VALUES ('o-fk-check', 999999, CURRENT_TIMESTAMP)"),
            "user_id 引用不存在的用户应违反 fk_wechat_bind_user 外键约束");
        org.junit.jupiter.api.Assertions.assertNotNull(fk);
    }

    @Test
    void 用例2_逆向回滚_DROP_TABLE后表消失() {
        // 模拟 V10 回滚段（V10.sql 文件尾部注释中的 DROP TABLE）
        jdbcTemplate.execute("DROP TABLE wechat_bind");

        Integer tableCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = 'wechat_bind'",
            Integer.class);
        assertEquals(0, tableCount, "回滚（DROP TABLE）后 wechat_bind 应消失");
    }
}
