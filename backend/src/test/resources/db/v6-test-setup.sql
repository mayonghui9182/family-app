-- ============================================================
-- v6-test-setup.sql：V6 迁移测试前置种子数据（H2 测试专用）
-- 对齐 V4__Add_Family_Member_System.sql 的演示家庭种子
-- 用途：供 V6.sql 的 FK 引用（families/users/categories）+ 种子模板 category_id 引用
-- 幂等：DELETE 清理旧数据后 INSERT，保证重复运行不撞唯一约束
-- ============================================================

-- 清理预置种子数据（按 FK 依赖顺序：categories → families → users）
-- 注意：DELETE FROM families 会级联删除 V6 表的 family_id 数据（ON DELETE CASCADE）
DELETE FROM family_members WHERE family_id = 1;
DELETE FROM categories WHERE family_id = 1;
DELETE FROM families WHERE id = 1;
DELETE FROM users WHERE id = 1;

-- 演示用户 id=1（密码 BCrypt("familyapp123")，由 PasswordEncoderTest 校验）
INSERT INTO users (id, email, nickname, password_hash, created_at, updated_at) VALUES
    (1, 'demo@family.app', '爸爸', '$2a$10$wNiDkKxBS6kTaY9QAfMesuf53eDURXSAGUx0mSXyHiZcWKQjhJOGy', NOW(), NOW());

-- 演示家庭 id=1
INSERT INTO families (id, name, created_by, created_at, updated_at) VALUES
    (1, 'Demo家', 1, NOW(), NOW());

-- 创建任务命令会实时校验活跃成员资格
INSERT INTO family_members (family_id, user_id, role, joined_at) VALUES
    (1, 1, 'OWNER', NOW());

-- 演示家庭 4 个默认分类（家务/育儿/工作/财务），V6 种子模板引用 category_id=1/2/4
INSERT INTO categories (id, name, color, sort_order, family_id, created_by, created_at, updated_at) VALUES
    (1, '家务', '#67C23A', 1, 1, 1, NOW(), NOW()),
    (2, '育儿', '#E6A23C', 2, 1, 1, NOW(), NOW()),
    (3, '工作', '#409EFF', 3, 1, 1, NOW(), NOW()),
    (4, '财务', '#F56C6C', 4, 1, 1, NOW(), NOW());