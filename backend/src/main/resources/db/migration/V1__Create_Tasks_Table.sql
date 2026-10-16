CREATE TABLE IF NOT EXISTS tasks (
    id           BIGSERIAL PRIMARY KEY,
    title        VARCHAR(200)   NOT NULL,
    description  TEXT,
    due_date     DATE,
    status       VARCHAR(20)    NOT NULL DEFAULT 'TODO'
        CONSTRAINT chk_tasks_status
        CHECK (status IN ('TODO','IN_PROGRESS','DONE','CANCELLED')),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at   TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_tasks_status_due_date
    ON tasks (status, due_date);
