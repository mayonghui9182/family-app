-- 指派提醒与任务同事务落库；投递租约和状态与任务五值状态机独立。
CREATE TABLE task_notifications (
    id BIGSERIAL PRIMARY KEY,
    family_id BIGINT NOT NULL REFERENCES families(id),
    task_id BIGINT NOT NULL REFERENCES tasks(id),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_task_notification_status CHECK (status IN ('PENDING','SENDING','SENT','SKIPPED','FAILED'))
);
CREATE INDEX idx_task_notification_pending ON task_notifications(family_id, status, available_at);
