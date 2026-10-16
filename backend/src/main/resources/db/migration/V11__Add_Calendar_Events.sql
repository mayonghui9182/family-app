-- ========================================================================
-- V11: Add Calendar Events（Change 13 日程/日历）
-- calendar_events：独立日程实体（一次性/重复，带时间点和提醒）
-- tasks 扩展：due_time + source_event_id
-- ========================================================================
-- H2 兼容说明（沿用 V6/V10 策略）：
--   1. JSONB 在 H2 MODE=PostgreSQL 下原生支持
--   2. partial unique index H2 不支持，在 afterMigrate 回调中执行（生产 PG）
--   3. CHECK 约束 H2 支持
-- ========================================================================

-- ============================================================
-- Step 1: calendar_events 表
-- ============================================================
CREATE TABLE IF NOT EXISTS calendar_events (
    id                    BIGSERIAL PRIMARY KEY,
    family_id             BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    title                 VARCHAR(200) NOT NULL,
    description           TEXT,
    event_date            DATE,
    event_time            VARCHAR(5) NOT NULL,
    recurrence            JSONB,
    reminder_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    assignee_profile_id   BIGINT REFERENCES person_profiles(id) ON DELETE SET NULL,
    category_id           BIGINT REFERENCES categories(id) ON DELETE SET NULL,
    is_active             BOOLEAN NOT NULL DEFAULT TRUE,
    created_by            BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at            TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_ce_time_format CHECK (event_time ~ '^\d{2}:\d{2}$'),
    CONSTRAINT chk_ce_date_recurrence CHECK (
        (event_date IS NOT NULL AND recurrence IS NULL)
        OR (event_date IS NULL AND recurrence IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_calendar_events_family ON calendar_events (family_id);
CREATE INDEX IF NOT EXISTS idx_calendar_events_active ON calendar_events (family_id, is_active);

-- ============================================================
-- Step 2: tasks 表扩展
-- ============================================================
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS due_time VARCHAR(5);
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS source_event_id BIGINT;

-- 常规索引（H2 兼容）；partial unique 在 afterMigrate 中补建（生产 PG）
CREATE INDEX IF NOT EXISTS idx_tasks_source_event ON tasks (source_event_id, due_date);
