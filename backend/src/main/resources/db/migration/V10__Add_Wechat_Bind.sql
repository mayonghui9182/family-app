-- ========================================================================
-- V10: Add WeChat Bind（Change 11 微信登录绑定）
-- wechat_bind：微信 openid ↔ family-app user_id 绑定关系
-- openid 永不离开服务端；绑定数据归 backend 所有（wechat-service 无 DB）
-- ========================================================================
-- H2 兼容说明（沿用 V6/V9 策略）：
--   1. BIGSERIAL / TIMESTAMP WITH TIME ZONE / IF NOT EXISTS 在 H2 MODE=PostgreSQL
--      下均已实测可用（V6/V9 同款）
--   2. 幂等写法：H2 ddl-auto=create-drop 环境 Hibernate 先建表，V10 自动跳过；
--      生产 PG 由 Flyway 单跑新增
-- ========================================================================

CREATE TABLE IF NOT EXISTS wechat_bind (
    id          BIGSERIAL PRIMARY KEY,
    openid      VARCHAR(64) NOT NULL,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    bound_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    -- 一个微信 openid 最多绑定一个 family-app 账号（幂等绑定靠它兜底）
    CONSTRAINT uq_wechat_bind_openid UNIQUE (openid)
);

-- 按 user_id 反查绑定关系（注销/审计场景）
CREATE INDEX IF NOT EXISTS idx_wechat_bind_user ON wechat_bind(user_id);

-- ============================================================
-- 回滚段（V10 逆向，供真机故障回退使用；Flyway 正向迁移不执行）：
--   DROP INDEX IF EXISTS idx_wechat_bind_user;
--   DROP TABLE IF EXISTS wechat_bind;
-- ============================================================
