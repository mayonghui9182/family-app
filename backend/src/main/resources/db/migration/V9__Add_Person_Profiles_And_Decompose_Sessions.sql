-- ========================================================================
-- V9: Add Person Profiles + Agent Decompose Sessions
-- 2 张新表 + tasks.assignee_profile_id 过渡列 + rule_templates.profile_types 列
-- 全 family_id 多租户隔离
-- ========================================================================
-- H2 兼容说明（参照 V6 同款策略）：
--   1. JSONB 在 H2 MODE=PostgreSQL 下原生支持（V6 实测通过），直接使用
--   2. partial index（CREATE INDEX ... WHERE deleted_at IS NULL）H2 不支持
--      本文件使用常规索引；partial unique 升级在 afterMigrate__agent_reader.sql
--      回调中执行（生产 PG），与 V6 uk_rt_family_name_active 同款策略
--   3. TIMESTAMP WITH TIME ZONE 替代 TIMESTAMPTZ（H2 兼容，V6 同款）
--   4. CONSTRAINT 命名 chk_pp_* / chk_ads_* 与 V6 chk_* 风格一致
--   5. 幂等写法（PG + H2 共同支持）：CREATE TABLE/INDEX/COLUMN IF NOT EXISTS
--      —— H2 ddl-auto=create-drop 环境先建对象后 V9 会跳过；生产 Flyway 单跑新增
--   6. 默认值策略：tags / profile_data / messages 等 NOT NULL 列**不在 DDL 给 DEFAULT**
--      —— H2 解析 DEFAULT '{}'::jsonb / DEFAULT '{}' 会语法失败；由实体 @PrePersist 兜底
--      （PersonProfile.onCreate / DecomposeSession.onCreate / Service 层保存前赋值）
-- ========================================================================

-- ============================================================
-- §1.1 person_profiles — 家庭人员档案（注册用户 + 虚拟成员）
-- 双字段过渡策略：user_id NULL 表示虚拟成员（宝宝/宠物等）
-- ========================================================================
CREATE TABLE IF NOT EXISTS person_profiles (
    id              BIGSERIAL PRIMARY KEY,
    family_id       BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    user_id         BIGINT NULL REFERENCES users(id) ON DELETE SET NULL,
    profile_type    VARCHAR(10) NOT NULL
        CONSTRAINT chk_pp_profile_type
        CHECK (profile_type IN ('BABY','ELDER','ADULT','PET','OTHER')),
    name            VARCHAR(50) NOT NULL,
    avatar_emoji    VARCHAR(8) NOT NULL,
    gender          VARCHAR(6) NULL
        CONSTRAINT chk_pp_gender
        CHECK (gender IS NULL OR gender IN ('MALE','FEMALE','OTHER')),
    birthday        DATE NULL,
    relation        VARCHAR(20) NOT NULL,
    tags            JSONB NOT NULL,
    profile_data    JSONB NOT NULL,
    created_by      BIGINT NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP WITH TIME ZONE NULL
);

-- H2 不支持 partial unique（WHERE deleted_at IS NULL），用常规 unique 替代
-- 生产 PG 通过 afterMigrate 回调升级为 partial unique index（V6 同款策略）
CREATE UNIQUE INDEX IF NOT EXISTS uk_pp_family_name ON person_profiles (family_id, name);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pp_family_user ON person_profiles (family_id, user_id);
CREATE INDEX IF NOT EXISTS idx_pp_family_type ON person_profiles (family_id, profile_type);
CREATE INDEX IF NOT EXISTS idx_pp_family_created_by ON person_profiles (family_id, created_by);

-- ============================================================
-- §1.3 tasks.assignee_profile_id — 过渡列（双字段共存本 Change 全程）
-- 旧 assignee_id 保留 1 大版本；CreateTaskRequest 传 assigneeProfileId 优先
-- 两列都传且不一致 40010「指派给谁参数冲突，请仅使用新档案字段」
-- ========================================================================
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS assignee_profile_id BIGINT REFERENCES person_profiles(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_tasks_assignee_profile ON tasks (assignee_profile_id);

-- ============================================================
-- §1.4 agent_decompose_sessions — AI 拆解会话草稿箱
-- D-1~D-4 只写本表（session 中间态）；D-5/D-6/D-7 终态才写业务表
-- 放弃操作零写入（status=CANCELLED 仅 UPDATE 本表）
-- ========================================================================
CREATE TABLE IF NOT EXISTS agent_decompose_sessions (
    id                       BIGSERIAL PRIMARY KEY,
    family_id                BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    profile_id               BIGINT NULL REFERENCES person_profiles(id) ON DELETE SET NULL,
    session_type             VARCHAR(20) NOT NULL
        CONSTRAINT chk_ads_session_type
        CHECK (session_type IN ('TASK_CREATE','PROFILE_INTERVIEW')),
    status                   VARCHAR(15) NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT chk_ads_status
        CHECK (status IN ('ACTIVE','CONFIRMED','CANCELLED','ARCHIVED')),
    init_task_title          VARCHAR(200) NULL,
    init_task_category_id    BIGINT NULL REFERENCES categories(id) ON DELETE SET NULL,
    init_task_description    TEXT NULL,
    init_task_priority       VARCHAR(10) NULL,
    init_task_due_date       DATE NULL,
    init_task_id             BIGINT NULL,
    init_profile_id_snapshot JSONB NULL,
    messages                 JSONB NOT NULL,
    extracted_profile_patch  JSONB NULL,
    proposed_tasks           JSONB NULL,
    created_by               BIGINT NOT NULL,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ads_family_status ON agent_decompose_sessions (family_id, status);
CREATE INDEX IF NOT EXISTS idx_ads_profile ON agent_decompose_sessions (profile_id);
CREATE INDEX IF NOT EXISTS idx_ads_created_by ON agent_decompose_sessions (created_by);

-- ============================================================
-- §2.2 rule_templates.profile_types — 任务建议 similar_templates 匹配条件
-- NULL 全适配；非 NULL 时数组任一匹配即入选
-- ========================================================================
ALTER TABLE rule_templates ADD COLUMN IF NOT EXISTS profile_types JSONB DEFAULT NULL;
COMMENT ON COLUMN rule_templates.profile_types IS '适配的档案类型数组(JSON数组)；NULL 全适配；S-1 similar_templates 匹配条件';
