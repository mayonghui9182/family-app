DELETE FROM tasks;
DELETE FROM categories;
-- ========================================================================
-- V4: Add Family Member System (BREAKING CHANGE)
-- 前置：line 1-2 先清空 tasks/categories 存量 user_id 数据
--       （防止后续 NOT NULL 列改造时存量 NULL 值导致 SQL 失败）
-- 后续步骤：4 张新表 → tasks ALTER → categories ALTER → 种子数据 → setval
-- ========================================================================

-- ========================================================================
-- 1. 新建表：users
-- ========================================================================
CREATE TABLE IF NOT EXISTS users (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(120),
    phone           VARCHAR(20),
    nickname        VARCHAR(50)     NOT NULL,
    password_hash   VARCHAR(255)    NOT NULL,
    avatar          VARCHAR(255),
    access_token    VARCHAR(64),
    token_updated_at TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_users_login CHECK (email IS NOT NULL OR phone IS NOT NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_email
    ON users (email) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_phone
    ON users (phone) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_users_access_token
    ON users (access_token) WHERE access_token IS NOT NULL;

-- ========================================================================
-- 2. 新建表：families
-- ========================================================================
CREATE TABLE IF NOT EXISTS families (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(50)     NOT NULL,
    description  VARCHAR(200),
    avatar       VARCHAR(255),
    created_by   BIGINT          NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_families_created_by ON families (created_by);

-- ========================================================================
-- 3. 新建表：family_members
-- ========================================================================
CREATE TABLE IF NOT EXISTS family_members (
    id          BIGSERIAL PRIMARY KEY,
    family_id   BIGINT  NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    user_id     BIGINT  NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    role        VARCHAR(10) NOT NULL,
    joined_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    left_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_fm_role CHECK (role IN ('OWNER','ADULT','CHILD')),
    CONSTRAINT uk_fm_family_user UNIQUE (family_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_fm_user ON family_members (user_id);
CREATE INDEX IF NOT EXISTS idx_fm_active
    ON family_members (family_id, user_id) WHERE left_at IS NULL;

-- ========================================================================
-- 4. 新建表：invites
-- ========================================================================
CREATE TABLE IF NOT EXISTS invites (
    id          BIGSERIAL PRIMARY KEY,
    family_id   BIGINT  NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    inviter_id  BIGINT  NOT NULL REFERENCES users(id)    ON DELETE RESTRICT,
    code        VARCHAR(6)  NOT NULL,
    max_uses    INT         NOT NULL DEFAULT 1,
    used_count  INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE,
    status      VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT chk_invites_status CHECK (status IN ('ACTIVE','USED','EXPIRED','CANCELLED')),
    CONSTRAINT chk_invites_used   CHECK (used_count <= max_uses)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_invites_active_code
    ON invites (code) WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_invites_family ON invites (family_id);
CREATE INDEX IF NOT EXISTS idx_invites_status ON invites (status);

-- ========================================================================
-- 5. tasks 表 BREAKING 改造：DROP VARCHAR user_id → ADD family_id + created_by + assignee_id
-- ========================================================================
ALTER TABLE tasks DROP COLUMN IF EXISTS user_id;

ALTER TABLE tasks ADD COLUMN family_id   BIGINT;
ALTER TABLE tasks ADD COLUMN created_by  BIGINT REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE tasks ADD COLUMN assignee_id BIGINT REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE tasks ALTER COLUMN family_id SET NOT NULL;

ALTER TABLE tasks ADD CONSTRAINT fk_tasks_family
    FOREIGN KEY (family_id) REFERENCES families(id) ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS idx_tasks_family     ON tasks (family_id);
CREATE INDEX IF NOT EXISTS idx_tasks_created_by ON tasks (created_by);
CREATE INDEX IF NOT EXISTS idx_tasks_assignee   ON tasks (assignee_id);

-- ========================================================================
-- 6. categories 表 BREAKING 改造：DROP VARCHAR user_id → ADD family_id + created_by
--    tasks.category_id → categories(id) 的 FK fk_tasks_category 引用 categories.id 主键，
--    主键列未变，此处无需重建。
-- ========================================================================
DROP INDEX IF EXISTS uk_category_user_name;

ALTER TABLE categories DROP COLUMN IF EXISTS user_id;

ALTER TABLE categories ADD COLUMN family_id  BIGINT;
ALTER TABLE categories ADD COLUMN created_by BIGINT REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE categories ALTER COLUMN family_id SET NOT NULL;

ALTER TABLE categories ADD CONSTRAINT fk_categories_family
    FOREIGN KEY (family_id) REFERENCES families(id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX IF NOT EXISTS uk_category_family_name
    ON categories (family_id, name) WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_categories_family     ON categories (family_id);
CREATE INDEX IF NOT EXISTS idx_categories_created_by  ON categories (created_by);

-- ========================================================================
-- 7. 演示家庭种子数据：1 用户 + 1 家庭 + 1 OWNER 成员 + 4 默认分类
--    password_hash: BCrypt("familyapp123")，由 PasswordEncoderTest 校验一致性。
-- ========================================================================
INSERT INTO users (id, email, phone, nickname, password_hash, avatar, created_at, updated_at) VALUES
    (1, 'demo@family.app', NULL, '爸爸', '$2a$10$wNiDkKxBS6kTaY9QAfMesuf53eDURXSAGUx0mSXyHiZcWKQjhJOGy', NULL, NOW(), NOW());

INSERT INTO families (id, name, description, avatar, created_by, created_at, updated_at) VALUES
    (1, 'Demo家', NULL, NULL, 1, NOW(), NOW());

INSERT INTO family_members (id, family_id, user_id, role, joined_at) VALUES
    (1, 1, 1, 'OWNER', NOW());

INSERT INTO categories (id, name, color, icon, sort_order, family_id, created_by, created_at, updated_at) VALUES
    (1, '家务', '#67C23A', NULL, 1, 1, 1, NOW(), NOW()),
    (2, '育儿', '#E6A23C', NULL, 2, 1, 1, NOW(), NOW()),
    (3, '工作', '#409EFF', NULL, 3, 1, 1, NOW(), NOW()),
    (4, '财务', '#F56C6C', NULL, 4, 1, 1, NOW(), NOW());

-- ========================================================================
-- 8. 同步 sequence 最后值，后续 INSERT 不撞主键
-- ========================================================================
SELECT setval('users_id_seq',          1);
SELECT setval('families_id_seq',       1);
SELECT setval('family_members_id_seq', 1);
SELECT setval('categories_id_seq',     4);