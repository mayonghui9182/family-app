package com.familyapp.common;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成演示用户 BCrypt 密码哈希值，供 V4 Flyway 迁移种子数据硬编码使用。
 * <p>
 * <b>操作说明</b>：运行本测试后，从 stdout 复制 {@code LITERAL_HASH} 的值，
 * 粘贴到 {@code V4__Add_Family_Member_System.sql} 种子 INSERT 语句中。
 * 本测试同时反向验证 SQL 字面量与明文密码匹配，防止 hash 与密码不一致。
 * </p>
 */
class PasswordEncoderTest {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

    /** V4 SQL 种子数据的演示用户明文密码。 */
    private static final String DEMO_PASSWORD = "familyapp123";

    /**
     * 将此处替换为本测试第一次执行时 stdout 打印的 hash 值，
     * 与 V4 SQL 中的字面量保持一致。
     * <p>
     * 占位值使用 {@code "{gen-on-first-run}"}；首次运行本测试会断言失败并打印真实 hash。
     * </p>
     */
    private static final String LITERAL_HASH_FROM_V4_SQL = "$2a$10$wNiDkKxBS6kTaY9QAfMesuf53eDURXSAGUx0mSXyHiZcWKQjhJOGy";

    @Test
    void generateDemoPasswordHashAndVerifyV4SqlLiteral() {
        // 1. 生成一个新的 BCrypt hash（salt 每次不同，复制到 V4 SQL + LITERAL_HASH_FROM_V4_SQL 常量）
        String freshHash = ENCODER.encode(DEMO_PASSWORD);
        System.out.println("========================================");
        System.out.println("=== PasswordEncoderTest: DEMO HASH ===");
        System.out.println("复制下方整行字符串到 V4 SQL INSERT users.password_hash 和 本类 LITERAL_HASH_FROM_V4_SQL：");
        System.out.println(freshHash);
        System.out.println("========================================");

        // 2. 断言 hash 本身可被 matches（必真，确保 encode→matches 往返）
        assertTrue(ENCODER.matches(DEMO_PASSWORD, freshHash),
                "ENCODER.matches 必须验证 encode 刚生成的 hash");

        // 3. 若非首次运行（已把 hash 粘贴到 LITERAL_HASH_FROM_V4_SQL），验证 V4 SQL 字面量匹配密码
        if (!LITERAL_HASH_FROM_V4_SQL.equals("{gen-on-first-run}")) {
            assertTrue(ENCODER.matches(DEMO_PASSWORD, LITERAL_HASH_FROM_V4_SQL),
                    "V4 SQL 中的密码字面量 hash 必须能被明文 'familyapp123' matches。"
                            + "若失败：删除 V4 SQL 和本类 LITERAL_HASH_FROM_V4_SQL 中的旧 hash，"
                            + "重跑本测试并重新复制新 hash。");
        }
    }
}