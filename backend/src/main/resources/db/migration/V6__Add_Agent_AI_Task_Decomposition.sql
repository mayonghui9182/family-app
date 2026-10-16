-- ========================================================================
-- V6: Add Agent AI Task Decomposition
-- 7 张新表（全 family_id 多租户隔离）+ tasks.status 防御纵深 CHECK + 3 条种子模板
-- ========================================================================
-- H2 兼容说明：
--   1. JSONB 在 H2 MODE=PostgreSQL 下原生支持（实测通过），直接使用
--   2. partial index（CREATE INDEX ... WHERE）H2 不支持，本文件使用常规索引
--      partial unique 升级在 afterMigrate__agent_reader.sql 回调中执行（生产 PG）
--   3. agent_reader DO 块 + 列级 GRANT 同在 afterMigrate 回调中执行
--   4. summary_execution_log 的分布式锁表达式索引 uk_sel_family_run_date
--      同在 afterMigrate 回调中补建（H2 不支持表达式索引）
-- ========================================================================

-- ============================================================
-- Step 3: tasks.status 防御纵深 CHECK
-- V2 已建 tasks_status_check（5 值），此处统一命名为 chk_tasks_status
-- ============================================================
ALTER TABLE tasks DROP CONSTRAINT IF EXISTS tasks_status_check;
ALTER TABLE tasks DROP CONSTRAINT IF EXISTS chk_tasks_status;
ALTER TABLE tasks ADD CONSTRAINT chk_tasks_status
    CHECK (status IN ('DRAFT','PENDING','IN_PROGRESS','COMPLETED','CANCELLED'));

-- ============================================================
-- Step 2.1: subtasks — 子待办表
-- ============================================================
CREATE TABLE IF NOT EXISTS subtasks (
    id              BIGSERIAL PRIMARY KEY,
    family_id       BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    parent_task_id  BIGINT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    title           VARCHAR(200) NOT NULL,
    description     TEXT,
    sort_order      INT NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT chk_subtasks_status
        CHECK (status IN ('DRAFT','PENDING','IN_PROGRESS','COMPLETED','CANCELLED')),
    is_ai_generated BOOLEAN NOT NULL DEFAULT FALSE,
    created_by      BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_subtasks_family ON subtasks (family_id);
CREATE INDEX IF NOT EXISTS idx_subtasks_parent_task ON subtasks (parent_task_id, sort_order);
CREATE INDEX IF NOT EXISTS idx_subtasks_ai_generated ON subtasks (parent_task_id, is_ai_generated);

-- ============================================================
-- Step 2.2: notes — 注意事项表（多态指向 tasks / subtasks）
-- ============================================================
CREATE TABLE IF NOT EXISTS notes (
    id          BIGSERIAL PRIMARY KEY,
    family_id   BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    target_type VARCHAR(10) NOT NULL
        CONSTRAINT chk_notes_target_type
        CHECK (target_type IN ('task','subtask')),
    target_id   BIGINT NOT NULL,
    content     TEXT NOT NULL,
    created_by  BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notes_family ON notes (family_id);
CREATE INDEX IF NOT EXISTS idx_notes_target ON notes (target_type, target_id);

-- ============================================================
-- Step 2.3: rule_templates — 统一模板表（decomposition + routine）
-- 注意：parent_version_id FK 在 rule_template_versions 建表后 ALTER 补（循环依赖）
-- ============================================================
CREATE TABLE IF NOT EXISTS rule_templates (
    id               BIGSERIAL PRIMARY KEY,
    family_id        BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    type             VARCHAR(20) NOT NULL
        CONSTRAINT chk_rt_type
        CHECK (type IN ('decomposition','routine')),
    name             VARCHAR(100) NOT NULL,
    category_id      BIGINT REFERENCES categories(id) ON DELETE SET NULL,
    conditions       JSONB,
    content          JSONB NOT NULL,
    is_active        BOOLEAN NOT NULL DEFAULT TRUE,
    version          INT NOT NULL DEFAULT 1,
    parent_version_id BIGINT,
    created_by       BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rt_family_type ON rule_templates (family_id, type, is_active);
CREATE INDEX IF NOT EXISTS idx_rt_category ON rule_templates (category_id);
-- H2 不支持 partial unique（WHERE is_active = TRUE），用常规 unique 替代
-- 生产 PG 通过 afterMigrate 回调升级为 partial unique index
CREATE UNIQUE INDEX IF NOT EXISTS uk_rt_family_name_active ON rule_templates (family_id, name);

-- ============================================================
-- Step 2.4: rule_template_versions — 版本历史快照
-- ============================================================
CREATE TABLE IF NOT EXISTS rule_template_versions (
    id                BIGSERIAL PRIMARY KEY,
    family_id         BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    template_id       BIGINT NOT NULL REFERENCES rule_templates(id) ON DELETE CASCADE,
    version           INT NOT NULL,
    content           JSONB NOT NULL,
    change_summary    TEXT,
    is_important      BOOLEAN NOT NULL DEFAULT FALSE,
    parent_version_id BIGINT REFERENCES rule_template_versions(id) ON DELETE SET NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_rtv_template_version ON rule_template_versions (template_id, version);
CREATE INDEX IF NOT EXISTS idx_rtv_family_template ON rule_template_versions (family_id, template_id);

-- 补 rule_templates.parent_version_id FK（循环依赖，建表后 ALTER 补）
ALTER TABLE rule_templates DROP CONSTRAINT IF EXISTS fk_rt_parent_version;
ALTER TABLE rule_templates ADD CONSTRAINT fk_rt_parent_version
    FOREIGN KEY (parent_version_id) REFERENCES rule_template_versions(id) ON DELETE SET NULL;

-- ============================================================
-- Step 2.5: task_schedules — 一次性定时任务
-- ============================================================
CREATE TABLE IF NOT EXISTS task_schedules (
    id              BIGSERIAL PRIMARY KEY,
    family_id       BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    trigger_date    DATE NOT NULL,
    target_template JSONB NOT NULL,
    is_executed     BOOLEAN NOT NULL DEFAULT FALSE,
    created_by      BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ts_family_date_executed ON task_schedules (family_id, trigger_date, is_executed);

-- ============================================================
-- Step 2.6: task_triggers — 依赖触发（on_complete）
-- ============================================================
CREATE TABLE IF NOT EXISTS task_triggers (
    id              BIGSERIAL PRIMARY KEY,
    family_id       BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    source_task_id  BIGINT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    trigger_type    VARCHAR(20) NOT NULL DEFAULT 'on_complete'
        CONSTRAINT chk_tt_type
        CHECK (trigger_type IN ('on_complete')),
    target_template JSONB NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_by      BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tt_source_active ON task_triggers (source_task_id, is_active);

-- ============================================================
-- Step 2.7: summary_execution_log — 每日总结执行日志（按家庭维度）
-- 分布式锁：uk_sel_family_run_date UNIQUE(family_id, run_at::date)
-- H2 不支持表达式索引，此处先建常规索引；生产 PG 在 afterMigrate 回调补表达式 unique
-- ============================================================
CREATE TABLE IF NOT EXISTS summary_execution_log (
    id                BIGSERIAL PRIMARY KEY,
    family_id         BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    run_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    status            VARCHAR(10) NOT NULL
        CONSTRAINT chk_sel_status
        CHECK (status IN ('success','failed')),
    error_message     TEXT,
    categories_updated JSONB,
    trigger_source    VARCHAR(10) NOT NULL DEFAULT 'auto'
        CONSTRAINT chk_sel_trigger
        CHECK (trigger_source IN ('auto','manual')),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sel_family_run_at ON summary_execution_log (family_id, run_at);

-- ============================================================
-- Step 3: 3 条种子模板（演示家庭 family_id=1）
-- ① 周末大扫除 decomposition（category_id=1 家务）
-- ② 上学日检查书包 routine weekly（category_id=2 育儿）
-- ③ 月度记账 routine monthly（category_id=4 财务）
-- ============================================================
DELETE FROM rule_templates WHERE family_id = 1; -- 幂等：清理旧种子避免唯一约束冲突
INSERT INTO rule_templates (family_id, type, name, category_id, conditions, content, is_active, version, created_by, created_at, updated_at) VALUES
    -- ① 周末大扫除分解模板：5 步通用流程 + 注意事项
    (1, 'decomposition', '周末大扫除', 1, NULL,
     '{"common_steps":["收拾客厅","打扫卧室","清洁卫生间","拖厨房地面","倒垃圾"],"count":5,"notes":["大扫除建议周六上午开始"]}',
     TRUE, 1, 1, NOW(), NOW()),

    -- ② 上学日检查书包日常模板：周一至周五触发
    (1, 'routine', '上学日检查书包', 2,
     '{"type":"weekly","days":[1,2,3,4,5]}',
     '{"title":"检查书包","description":"每晚检查孩子书包是否装好次日课本","priority":"HIGH","needs_ai":false}',
     TRUE, 1, 1, NOW(), NOW()),

    -- ③ 月度记账日常模板：每月1号触发
    (1, 'routine', '月度记账', 4,
     '{"type":"monthly","day":1}',
     '{"title":"月度记账","description":"每月1号整理上月家庭收支","priority":"MEDIUM","needs_ai":false}',
     TRUE, 1, 1, NOW(), NOW());