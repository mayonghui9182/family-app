-- V2: Expand Task Model
-- Add user_id, category_id, priority, source, expires_at columns
-- Migrate existing status values: TODO -> PENDING, DONE -> COMPLETED
-- Update CHECK constraint for 5-value status enum

ALTER TABLE tasks ADD COLUMN user_id VARCHAR(50) NOT NULL DEFAULT 'default';
ALTER TABLE tasks ADD COLUMN category_id BIGINT;
ALTER TABLE tasks ADD COLUMN priority VARCHAR(10) NOT NULL DEFAULT 'MEDIUM';
ALTER TABLE tasks ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE tasks ADD COLUMN expires_at TIMESTAMP;

-- Drop old CHECK constraint BEFORE updating data (PENDING/COMPLETED not allowed by V1 chk_tasks_status)
-- Drop both candidate names for safety across environments
ALTER TABLE tasks DROP CONSTRAINT IF EXISTS chk_tasks_status;
ALTER TABLE tasks DROP CONSTRAINT IF EXISTS tasks_status_check;

-- Migrate existing status values (TODO -> PENDING, DONE -> COMPLETED)
UPDATE tasks SET status = 'PENDING' WHERE status = 'TODO';
UPDATE tasks SET status = 'COMPLETED' WHERE status = 'DONE';

-- Add new CHECK constraint for 5-value status enum
ALTER TABLE tasks ADD CONSTRAINT tasks_status_check
    CHECK (status IN ('DRAFT','PENDING','IN_PROGRESS','COMPLETED','CANCELLED'));