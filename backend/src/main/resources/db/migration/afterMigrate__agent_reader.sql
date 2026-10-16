-- ========================================================================
-- afterMigrate 回调：agent_reader 角色创建 + 列级授权 + PG 专有索引升级
-- ========================================================================
-- H2 兼容说明：
--   1. 测试环境 Flyway 禁用（ddl-auto=create-drop），此回调不在测试中执行
--   2. 生产 PostgreSQL 在 Flyway migrate 完成后执行此回调
--   3. agent_reader 密码使用占位 placeholder-agent-reader-password，生产部署须通过环境变量
--      或外部脚本 ALTER ROLE agent_reader PASSWORD '...' 覆盖（不入库）
--   4. 本回调仅含 PG 专有语法（DO 块 / partial index WHERE / 表达式索引 / 列级 GRANT）
--      H2 不支持这些语法，故不放入 V6.sql（V6.sql 需在 @Sql 直接跑 H2）
--   5. 本脚本必须幂等：Flyway 每次 migrate 后都会重放全部 afterMigrate 回调
-- ========================================================================

-- ============================================================
-- Step 4.1: 创建 agent_reader 角色（IF NOT EXISTS，DO 块包裹）
-- 规格 §2.3：CREATE ROLE agent_reader NOINHERIT LOGIN PASSWORD '...'
-- ============================================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agent_reader') THEN
        CREATE ROLE agent_reader NOINHERIT LOGIN PASSWORD 'placeholder-agent-reader-password';
    END IF;
END $$;

-- ============================================================
-- Step 4.2: 现有 6 表列级授权（排除敏感列）
-- 规格 §2.3：绝对不授 users.password_hash / users.access_token /
--           invites 整表 / tasks.subtasks.deleted_at
-- ============================================================

-- users: 排除 password_hash / access_token / deleted_at
GRANT SELECT (id, email, phone, nickname, avatar, token_updated_at, created_at, updated_at)
    ON users TO agent_reader;

-- families: 排除 deleted_at
GRANT SELECT (id, name, description, avatar, created_by, created_at, updated_at)
    ON families TO agent_reader;

-- family_members: 全列（无敏感列）
GRANT SELECT (id, family_id, user_id, role, joined_at, left_at)
    ON family_members TO agent_reader;

-- tasks: 排除 deleted_at
GRANT SELECT (id, title, description, due_date, status, created_at, updated_at,
              category_id, priority, source, expires_at, family_id, created_by, assignee_id)
    ON tasks TO agent_reader;

-- categories: 排除 deleted_at
GRANT SELECT (id, name, color, icon, sort_order, family_id, created_by, created_at, updated_at)
    ON categories TO agent_reader;

-- invites: 整表不授权（规格明确排除，含 code / max_uses / used_count 等敏感运营字段）

-- ============================================================
-- Step 4.3: V6 七新表全列授权（无敏感列，整表 SELECT）
-- ============================================================
GRANT SELECT ON subtasks TO agent_reader;
GRANT SELECT ON notes TO agent_reader;
GRANT SELECT ON rule_templates TO agent_reader;
GRANT SELECT ON rule_template_versions TO agent_reader;
GRANT SELECT ON task_schedules TO agent_reader;
GRANT SELECT ON task_triggers TO agent_reader;
GRANT SELECT ON summary_execution_log TO agent_reader;

-- ============================================================
-- Step 4.5: PG 专有索引升级（V6.sql 中用常规索引/unique 替代，此处升级）
-- ============================================================

-- uk_rt_family_name_active 升级为 partial unique（WHERE is_active = TRUE）
-- 检查索引是否存在且已是 partial 版本，仅在需要时 DROP+CREATE
DO $$
DECLARE
    idx_exists boolean;
    idx_is_partial boolean;
BEGIN
    SELECT EXISTS(SELECT 1 FROM pg_indexes WHERE indexname = 'uk_rt_family_name_active') INTO idx_exists;
    IF NOT idx_exists THEN
        CREATE UNIQUE INDEX uk_rt_family_name_active
            ON rule_templates (family_id, name) WHERE is_active = TRUE;
    ELSE
        SELECT indexdef LIKE '%WHERE is_active = TRUE%' INTO idx_is_partial
        FROM pg_indexes WHERE indexname = 'uk_rt_family_name_active' LIMIT 1;
        IF NOT idx_is_partial THEN
            DROP INDEX uk_rt_family_name_active;
            CREATE UNIQUE INDEX uk_rt_family_name_active
                ON rule_templates (family_id, name) WHERE is_active = TRUE;
        END IF;
    END IF;
END $$;

-- ============================================================
-- Step 4.6: 分布式锁键 uk_sel_family_run_date（表达式唯一索引）
-- 防止同家庭同日重复跑每日总结：UNIQUE(family_id, <run_at 所在日>)
--
-- ⚠️ PG 实现注意（真机验证发现）：run_at 是 TIMESTAMPTZ，
--   直接写 (run_at::date) 属 STABLE 表达式（结果随会话 TimeZone 变化），
--   PostgreSQL 以 42P17「functions in index expression must be marked IMMUTABLE」拒绝。
--   故以 IMMUTABLE 函数把时区固定为 UTC：键语义 = 「UTC 日历日的家庭维度锁」。
--   总结调度器（Change 5）Insert 命中唯一冲突即视为当日已跑、skip。
-- ============================================================
CREATE OR REPLACE FUNCTION sel_run_date_utc(t timestamptz)
    RETURNS date
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
AS $fn$
    SELECT (t AT TIME ZONE 'UTC')::date
$fn$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_sel_family_run_date
    ON summary_execution_log (family_id, sel_run_date_utc(run_at));

-- ============================================================
-- Step 4.7: Change 8 新表/新列列级授权（追加段，不动已有 GRANT）
-- 规格 §2.3：agent_reader 只读 person_profiles 业务字段 + tasks 新过渡列
-- ============================================================

-- person_profiles: 9 列只读（排除 created_by/created_at/updated_at/deleted_at 等审计列）
-- agent-service 用于 B-3 family 维度查询档案 + AI 沟通时注入 profiles_full[]
GRANT SELECT (id, family_id, name, profile_type, profile_data, gender, birthday, relation, tags)
    ON person_profiles TO agent_reader;

-- tasks: Change 8 新过渡列（已有 GRANT 段不含此列，单独追加）
-- agent-service 用于 AI 拆解时读 assignee_profile_id 关联档案
GRANT SELECT (assignee_profile_id) ON tasks TO agent_reader;

-- Change 13: 日程任务幂等唯一索引（生产 PG 专用，H2 不支持 partial index）
CREATE UNIQUE INDEX IF NOT EXISTS uq_tasks_event_date
    ON tasks (source_event_id, due_date)
    WHERE source_event_id IS NOT NULL AND deleted_at IS NULL;
