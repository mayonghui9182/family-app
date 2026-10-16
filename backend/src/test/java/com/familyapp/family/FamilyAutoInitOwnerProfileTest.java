package com.familyapp.family;

import com.familyapp.profile.PersonProfile;
import com.familyapp.profile.PersonProfileRepository;
import com.familyapp.profile.ProfileType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * FamilyService.createFamily 自动建 owner 档案测试
 *
 * <p>验证对象：FamilyService.createFamily 事务末尾自动插 1 条 person_profiles
 *
 * <p>设计依据（plan Step 6 §FamilyService.createFamily + plan File Structure §FamilyAutoInitOwnerProfileTest）：
 * <ul>
 *   <li>createFamily 在事务最后插入 1 条 PersonProfile</li>
 *   <li>type=ADULT（owner 是注册用户，默认成人类型）</li>
 *   <li>userId=owner.id（关联注册用户）</li>
 *   <li>name=owner.nickname（档案名称与用户昵称一致）</li>
 *   <li>relation="本人"（owner 与自己的关系）</li>
 *   <li>avatarEmoji="👨"（ADULT 类型默认 emoji，Service 层兜底）</li>
 *   <li>createdBy=owner.id（创建人为 owner 自己）</li>
 * </ul>
 *
 * <p>用途（spec §1.3 老家庭数据迁移 + 新家庭自动建档）：
 * <ul>
 *   <li>新家庭创建：FamilyService.createFamily 自动建 owner 档案 1 条</li>
 *   <li>老家庭数据迁移：V9 afterMigrate 脚本遍历活跃 family_members，每人插 1 条档案</li>
 * </ul>
 *
 * <p>测试策略：
 * 通过 JdbcTemplate 直接 INSERT 一个新用户 + 调用 FamilyService.createFamily
 * （避免 MockMvc 走 /api/auth/register 全流程，简化前置依赖），
 * 然后用 JdbcTemplate 查 person_profiles 表验证 owner 档案已自动创建。
 *
 * <p>TDD 状态：
 * <ul>
 *   <li>RED 阶段（Step 1c）：PersonProfileRepository + FamilyService.createFamily 改造不存在 → 编译失败 → 1 用例 RED</li>
 *   <li>GREEN 阶段（Step 8）：Step 5 PersonProfile 实体 + Step 6 FamilyService 改造 → PASS</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FamilyAutoInitOwnerProfileTest {

    @Autowired
    private FamilyService familyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("createFamily → person_profiles COUNT=1，type=ADULT，name 匹配 owner.nickname")
    void createFamily_自动建owner档案() {
        // 预置：直接 INSERT 一个新用户（绕过 /api/auth/register 全流程）
        // 使用随机 email 避免与默认 test@test.com 冲突
        String uniqueEmail = "owner_auto_init_" + System.currentTimeMillis() + "@family.app";
        String ownerNickname = "家庭主人测试";
        jdbcTemplate.update(
            "INSERT INTO users (email, nickname, password_hash, created_at, updated_at) VALUES (?, ?, ?, NOW(), NOW())",
            uniqueEmail, ownerNickname, "$2a$10$placeholderHashForTestPurposeOnlyXX");
        Long ownerId = jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE email = ?", Long.class, uniqueEmail);

        // 调用 createFamily（应在事务末尾自动建 owner 档案）
        // 假设 CreateFamilyRequest 包含 familyName + ownerNickname + ownerEmail + ownerPassword 字段
        // （Step 6 实现者会按实际 FamilyService.createFamily 签名调整测试）
        var req = new com.familyapp.family.dto.CreateFamilyRequest();
        req.setFamilyName("测试家庭_" + System.currentTimeMillis());
        // ownerEmail/ownerPassword 在测试中可复用已 INSERT 的用户，或 createFamily 内部复用
        // 具体签名由 Step 6 实现者按现有 FamilyService.createFamily 调整

        var response = familyService.createFamily(req, ownerId);
        Long familyId = response.id(); // FamilyResponse 是 record，accessor = id()

        // 验证：person_profiles 表中该 family 有 1 条 owner 档案
        Integer profileCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person_profiles WHERE family_id = ? AND deleted_at IS NULL",
            Integer.class, familyId);
        assertEquals(1, profileCount, "新家庭应有 1 条 owner 自动建档");

        // 验证档案字段
        Map<String, Object> profileRow = jdbcTemplate.queryForMap(
            "SELECT * FROM person_profiles WHERE family_id = ? AND deleted_at IS NULL", familyId);

        assertEquals("ADULT", profileRow.get("profile_type"),
            "owner 档案类型应为 ADULT");
        assertEquals(ownerNickname, profileRow.get("name"),
            "档案 name 应匹配 owner.nickname");
        assertEquals("本人", profileRow.get("relation"),
            "owner 与自己的关系应为「本人」");
        assertEquals("👨", profileRow.get("avatar_emoji"),
            "ADULT 类型默认 emoji 应为 👨（Service 层兜底）");
        assertEquals(ownerId, profileRow.get("user_id"),
            "档案 user_id 应关联 owner.id");
        assertEquals(ownerId, profileRow.get("created_by"),
            "档案 created_by 应为 owner.id");
        assertNotNull(profileRow.get("id"), "档案 id 应非空");
    }
}
