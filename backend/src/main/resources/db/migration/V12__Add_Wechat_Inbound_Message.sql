-- ========================================================================
-- V12: Add WeChat Inbound Message（Change 14 微信私聊消息自动对接 AI 拆解）
-- wechat_inbound_message：公众号 1v1 入站文本的「幂等表 + 队列表」（表即队列）
-- 只增不改：不触碰任何既有表；openid 刻意不建外键（未绑定用户也要留痕排障）
-- ========================================================================
-- H2 兼容说明（沿用 V10/V11 策略）：
--   1. BIGSERIAL / TIMESTAMP WITH TIME ZONE / IF NOT EXISTS 在 H2 MODE=PostgreSQL 下已实测可用
--   2. CHECK 约束 H2 支持；轮询索引用普通复合索引而非 partial index——H2 不支持
--      CREATE INDEX ... WHERE，且该索引非唯一，partial 只省空间、不影响正确性
--   3. 幂等写法：H2 ddl-auto=create-drop 环境 Hibernate 先建表，V12 自动跳过；
--      生产 PG 由 Flyway 单跑新增
-- ========================================================================

CREATE TABLE IF NOT EXISTS wechat_inbound_message (
    id          BIGSERIAL PRIMARY KEY,
    -- 微信消息 ID：吸收微信侧超时重推，同一条消息只处理一次
    msg_id      VARCHAR(64)  NOT NULL,
    -- 不建外键：未绑定的 openid 也要落行留痕，skip_reason 是唯一线索来源
    openid      VARCHAR(64)  NOT NULL,
    -- 用户原文；受理侧超 200 字符截断，与 createDraft 的 title 截断口径一致
    content     VARCHAR(200) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    -- 五值状态机：PENDING 待处理 / RUNNING 处理中 / DONE 已建草稿并回推 / FAILED 重试耗尽 / SKIPPED 闸门拒绝
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts    INT          NOT NULL DEFAULT 0,
    -- 拆解草稿指针（agent_decompose_sessions.id），DONE 时回写；SKIPPED/未受理为空
    session_id  BIGINT,
    -- 闸门拒绝原因（snake_case 契约值：not_bound/no_family/role_denied/multi_family/intake_disabled/ai_disabled）
    skip_reason VARCHAR(32),
    last_error  VARCHAR(500),
    -- 受理时裁决出的归属，作业据此建草稿、不再重复解析（歧义时宁拒不猜，故二者皆空即代表未受理）
    family_id   BIGINT REFERENCES families(id) ON DELETE SET NULL,
    user_id     BIGINT REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_wim_msg_id UNIQUE (msg_id),
    CONSTRAINT chk_wim_status CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'SKIPPED'))
);

-- 轮询队列扫描：WHERE status = 'PENDING' ORDER BY received_at
CREATE INDEX IF NOT EXISTS idx_wim_pending ON wechat_inbound_message (status, received_at);
-- 按 openid 排查单个用户的历史入站
CREATE INDEX IF NOT EXISTS idx_wim_openid ON wechat_inbound_message (openid);

-- ============================================================
-- 回滚段（V12 逆向，供真机故障回退使用；Flyway 正向迁移不执行）：
--   DROP INDEX IF EXISTS idx_wim_pending;
--   DROP INDEX IF EXISTS idx_wim_openid;
--   DROP TABLE IF EXISTS wechat_inbound_message;
--   DELETE FROM flyway_schema_history WHERE version = '12';
-- 生产回退优先「关开关不删表」：WECHAT_INTAKE_ENABLED=false 即刻静默，
-- 表与其中 skip_reason 是事后排障的唯一线索来源。
-- ============================================================
