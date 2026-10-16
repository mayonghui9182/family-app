-- V3: Add Category Management
-- Create categories table with soft-delete and per-user uniqueness
-- Add FK from tasks.category_id to categories.id (ON DELETE SET NULL)
-- Seed 4 default categories for the MVP default user

CREATE TABLE IF NOT EXISTS categories (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(50)    NOT NULL,
    color        VARCHAR(20)    NOT NULL DEFAULT '#909399',
    icon         VARCHAR(50),
    sort_order   INT            NOT NULL DEFAULT 0,
    user_id      VARCHAR(50)    NOT NULL DEFAULT 'default',
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at   TIMESTAMP WITH TIME ZONE
);

-- Partial unique index: name unique per user among non-deleted categories.
-- Allows re-creating a soft-deleted name later (the deleted row is excluded).
CREATE UNIQUE INDEX IF NOT EXISTS uk_category_user_name
    ON categories (user_id, name)
    WHERE deleted_at IS NULL;

-- FK: tasks.category_id -> categories.id
-- ON DELETE SET NULL: soft-delete is the standard flow (does not trigger FK);
-- a hard delete is a fallback that nulls the reference instead of cascading.
ALTER TABLE tasks
    ADD CONSTRAINT fk_tasks_category
    FOREIGN KEY (category_id) REFERENCES categories(id)
    ON DELETE SET NULL;

-- Seed 4 default categories for the MVP default user.
INSERT INTO categories (name, color, icon, sort_order, user_id) VALUES
    ('家务', '#67C23A', NULL, 1, 'default'),
    ('育儿', '#E6A23C', NULL, 2, 'default'),
    ('工作', '#409EFF', NULL, 3, 'default'),
    ('财务', '#F56C6C', NULL, 4, 'default');