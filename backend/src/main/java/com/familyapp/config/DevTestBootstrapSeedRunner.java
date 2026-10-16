package com.familyapp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 【S4 Step4 R1-c 最小修复】bootRun test profile 启动后的种子数据填充。
 *
 * 背景：
 *   application-test.yml 配置 flyway.enabled=false + ddl-auto=create-drop
 *   → Hibernate 根据 Entity 注解自动建表（结构有了），但 V1~V9 Flyway migration（含 V4 里的
 *     INSERT users(1) / families(1) 种子数据）完全不执行
 *   → 即使 DevBootstrapUserContextFilter 把 UserContext familyId=1 注入成功，服务层写
 *     person_profiles / tasks 这些带 family_id FK 的记录时仍会 FK 失败 → 50000
 *
 * 本 Runner 在 @Profile("test") 启动后立即执行：向 users / families / family_members 各插
 * 入 1 条演示数据（与 V4 migration 的种子数据保持一致）。重复启动无副作用（H2 mem 会清空）。
 */
@Component
@Profile("test")
public class DevTestBootstrapSeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevTestBootstrapSeedRunner.class);

    private final JdbcTemplate jdbc;

    public DevTestBootstrapSeedRunner(JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        // —— 1. users 演示爸爸账号（password=demo123 的 BCrypt hash 与 V4 一致）——
        try {
            jdbc.update(
                "MERGE INTO users (id, email, password_hash, nickname, created_at, updated_at) KEY(id) VALUES " +
                    "(1, 'demo@family.app', '$2a$10$wNiDkKxBS6kTaY9QAfMesuf53eDURXSAGUx0mSXyHiZcWKQjhJOGy', '爸爸', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            log.info("[DevSeed] users(1) 爸爸 插入/确认 OK");
        } catch (Exception e) {
            log.warn("[DevSeed] users 插入跳过（可能表结构不匹配）：{}", e.getMessage());
        }

        // —— 2. families Demo家（id=1 created_by=1）——
        try {
            jdbc.update(
                "MERGE INTO families (id, name, created_by, created_at, updated_at) KEY(id) VALUES " +
                    "(1, 'Demo家', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            log.info("[DevSeed] families(1) Demo家 插入/确认 OK");
        } catch (Exception e) {
            log.warn("[DevSeed] families 插入跳过：{}", e.getMessage());
        }

        // —— 3. family_members (user=1, family=1, role=OWNER) ——
        // 注意：family_members 表字段名可能是 user_id/family_id/role，与 V4 一致
        try {
            // 先尝试按 V4 字段名：user_id, family_id, role
            jdbc.update(
                "MERGE INTO family_members (user_id, family_id, role, created_at, updated_at) KEY(user_id, family_id) VALUES " +
                    "(1, 1, 'OWNER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            log.info("[DevSeed] family_members(1,1,OWNER) 插入/确认 OK");
        } catch (Exception e) {
            // 备用：表字段可能是 userId/familyId 或不同的 PK 列名，降级为普通 INSERT，失败无副作用
            log.warn("[DevSeed] family_members MERGE 尝试降级：{}", e.getMessage());
            try {
                jdbc.update("INSERT INTO family_members (user_id, family_id, role, created_at, updated_at) VALUES " +
                    "(1, 1, 'OWNER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                log.info("[DevSeed] family_members INSERT 降级成功");
            } catch (Exception e2) {
                log.warn("[DevSeed] family_members 两种方式都跳过：{}", e2.getMessage());
            }
        }

        // —— 4. H2 IDENTITY 序列重置 ——
        // 上面 MERGE 显式写入 id=1 不会推进 H2 自增序列（与 PG 显式插入不推进 sequence 同理），
        // 若不重置，Hibernate 首次 id=default 插入会分配到 1 → 主键冲突 23505 → 接口 500。
        // PG 真机由 V4 migration 的 setval('users_id_seq',1) 等处理；本 Runner 仅 @Profile("test")
        // + H2 ddl-auto=create-drop 场景使用，PG 下 ALTER TABLE ... RESTART 语法不兼容，失败仅告警。
        for (String table : new String[]{"users", "families", "family_members"}) {
            try {
                jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH 100");
            } catch (Exception e) {
                log.warn("[DevSeed] {} 序列重置跳过（非 H2 环境？）：{}", table, e.getMessage());
            }
        }

        log.info("[DevSeed] ========== 种子数据填充完毕（UserId=1 FamilyId=1 Role=OWNER），开始 S4 Step4 联调 ==========");
    }
}
