-- 账号级恢复凭证不属于家庭业务数据，仅保存一次性凭证摘要。
CREATE TABLE password_resets (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ
);
