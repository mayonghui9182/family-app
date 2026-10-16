-- ========================================================================
-- V15: 微信公众号持续任务会话、可靠出站与家庭经验
--
-- 本迁移只新增表、索引和冗余候选键，不改变旧 intake/草稿表的语义。
-- JSONB、BIGSERIAL、TIMESTAMP WITH TIME ZONE 均兼容 PostgreSQL 与
-- H2 MODE=PostgreSQL；活动会话唯一性使用可空 active_slot，避免 partial index。
-- ========================================================================

-- 既有主键 id 已全局唯一；补充复合候选键仅用于让数据库验证关联对象的家庭一致性。
ALTER TABLE tasks ADD CONSTRAINT uq_tasks_id_family UNIQUE (id, family_id);
ALTER TABLE wechat_bind ADD CONSTRAINT uq_wechat_bind_id_user UNIQUE (id, user_id);

CREATE TABLE wechat_chat_context (
    id                          BIGSERIAL PRIMARY KEY,
    wechat_bind_id              BIGINT NOT NULL,
    user_id                     BIGINT NOT NULL,
    current_family_id           BIGINT NULL REFERENCES families(id) ON DELETE SET NULL,
    pending_family_selection    JSONB NULL,
    pending_history_delete      JSONB NULL,
    route_revision              BIGINT NOT NULL DEFAULT 0,
    lease_owner                 VARCHAR(100) NULL,
    lease_until                 TIMESTAMP WITH TIME ZONE NULL,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wcc_wechat_bind UNIQUE (wechat_bind_id),
    CONSTRAINT fk_wcc_bind_user FOREIGN KEY (wechat_bind_id, user_id)
        REFERENCES wechat_bind(id, user_id) ON DELETE CASCADE,
    CONSTRAINT chk_wcc_route_revision CHECK (route_revision >= 0),
    CONSTRAINT chk_wcc_lease_pair CHECK (
        (lease_owner IS NULL AND lease_until IS NULL)
        OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL)
    )
);

CREATE INDEX idx_wcc_family_bind
    ON wechat_chat_context (current_family_id, wechat_bind_id);
CREATE INDEX idx_wcc_lease
    ON wechat_chat_context (lease_until, wechat_bind_id);

CREATE TABLE wechat_task_conversations (
    id                          BIGSERIAL PRIMARY KEY,
    family_id                   BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    wechat_bind_id              BIGINT NOT NULL,
    user_id                     BIGINT NOT NULL,
    parent_conversation_id      BIGINT NULL,
    status                      VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    paused_from_status          VARCHAR(24) NULL,
    active_slot                 SMALLINT NULL DEFAULT 1,
    current_revision            INT NOT NULL DEFAULT 0,
    slot_state                  JSONB NOT NULL,
    rolling_summary             TEXT NULL,
    summary_through_message_id  BIGINT NULL,
    last_activity_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at                  TIMESTAMP WITH TIME ZONE NULL,
    version                     BIGINT NOT NULL DEFAULT 0,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wtc_id_family UNIQUE (id, family_id),
    CONSTRAINT uq_wtc_active_slot UNIQUE (wechat_bind_id, family_id, active_slot),
    CONSTRAINT fk_wtc_bind_user FOREIGN KEY (wechat_bind_id, user_id)
        REFERENCES wechat_bind(id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_wtc_parent_family FOREIGN KEY (parent_conversation_id, family_id)
        REFERENCES wechat_task_conversations(id, family_id),
    CONSTRAINT chk_wtc_status CHECK (
        status IN ('ACTIVE', 'AWAITING_CONFIRMATION', 'PAUSED', 'CONFIRMED', 'CANCELLED', 'EXPIRED')
    ),
    CONSTRAINT chk_wtc_paused_from CHECK (
        (status = 'PAUSED' AND paused_from_status IN ('ACTIVE', 'AWAITING_CONFIRMATION'))
        OR (status <> 'PAUSED' AND paused_from_status IS NULL)
    ),
    CONSTRAINT chk_wtc_active_slot CHECK (
        (status IN ('ACTIVE', 'AWAITING_CONFIRMATION', 'PAUSED') AND active_slot = 1)
        OR (status IN ('CONFIRMED', 'CANCELLED', 'EXPIRED') AND active_slot IS NULL)
    ),
    CONSTRAINT chk_wtc_revision CHECK (current_revision >= 0),
    CONSTRAINT chk_wtc_version CHECK (version >= 0)
);

CREATE INDEX idx_wtc_family_status_expiry
    ON wechat_task_conversations (family_id, status, expires_at);
CREATE INDEX idx_wtc_family_activity
    ON wechat_task_conversations (family_id, last_activity_at);
CREATE INDEX idx_wtc_family_parent
    ON wechat_task_conversations (family_id, parent_conversation_id);

CREATE TABLE wechat_conversation_messages (
    id                  BIGSERIAL PRIMARY KEY,
    wechat_account_id   VARCHAR(100) NOT NULL,
    external_msg_id     VARCHAR(100) NULL,
    openid              VARCHAR(64) NOT NULL,
    direction           VARCHAR(12) NOT NULL,
    content             TEXT NOT NULL,
    wechat_bind_id      BIGINT NULL,
    user_id             BIGINT NULL,
    family_id           BIGINT NULL REFERENCES families(id) ON DELETE CASCADE,
    conversation_id     BIGINT NULL,
    processing_status   VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts            INT NOT NULL DEFAULT 0,
    available_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    lease_owner         VARCHAR(100) NULL,
    lease_until         TIMESTAMP WITH TIME ZONE NULL,
    last_error          VARCHAR(1000) NULL,
    received_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at        TIMESTAMP WITH TIME ZONE NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wcm_id_family UNIQUE (id, family_id),
    CONSTRAINT uq_wcm_account_external UNIQUE (wechat_account_id, external_msg_id),
    CONSTRAINT fk_wcm_bind_user FOREIGN KEY (wechat_bind_id, user_id)
        REFERENCES wechat_bind(id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_wcm_conversation_family FOREIGN KEY (conversation_id, family_id)
        REFERENCES wechat_task_conversations(id, family_id) ON DELETE CASCADE,
    CONSTRAINT chk_wcm_direction CHECK (direction IN ('INBOUND', 'OUTBOUND', 'SYSTEM')),
    CONSTRAINT chk_wcm_processing_status CHECK (
        processing_status IN ('PENDING', 'WAITING_FAMILY', 'PROCESSING', 'PROCESSED', 'FAILED', 'SKIPPED')
    ),
    CONSTRAINT chk_wcm_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_wcm_inbound_external CHECK (
        direction <> 'INBOUND' OR external_msg_id IS NOT NULL
    ),
    CONSTRAINT chk_wcm_bind_user_pair CHECK (
        (wechat_bind_id IS NULL AND user_id IS NULL)
        OR (wechat_bind_id IS NOT NULL AND user_id IS NOT NULL)
    ),
    CONSTRAINT chk_wcm_conversation_family_pair CHECK (
        conversation_id IS NULL OR family_id IS NOT NULL
    ),
    CONSTRAINT chk_wcm_lease_pair CHECK (
        (lease_owner IS NULL AND lease_until IS NULL)
        OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL)
    )
);

CREATE INDEX idx_wcm_family_conversation_created
    ON wechat_conversation_messages (family_id, conversation_id, created_at);
CREATE INDEX idx_wcm_family_status_available
    ON wechat_conversation_messages (family_id, processing_status, available_at);
CREATE INDEX idx_wcm_queue
    ON wechat_conversation_messages (processing_status, available_at, received_at);
CREATE INDEX idx_wcm_bind_created
    ON wechat_conversation_messages (wechat_bind_id, created_at);
CREATE INDEX idx_wcm_account_openid_created
    ON wechat_conversation_messages (wechat_account_id, openid, created_at);

CREATE TABLE wechat_candidate_versions (
    id                  BIGSERIAL PRIMARY KEY,
    conversation_id     BIGINT NOT NULL,
    family_id           BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    revision            INT NOT NULL,
    candidate_payload   JSONB NOT NULL,
    rendered_preview    TEXT NOT NULL,
    source_message_id   BIGINT NOT NULL,
    model_name          VARCHAR(100) NOT NULL,
    prompt_version      VARCHAR(100) NOT NULL,
    experience_refs     JSONB NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wcv_id_family UNIQUE (id, family_id),
    CONSTRAINT uq_wcv_conversation_revision UNIQUE (conversation_id, revision),
    CONSTRAINT fk_wcv_conversation_family FOREIGN KEY (conversation_id, family_id)
        REFERENCES wechat_task_conversations(id, family_id) ON DELETE CASCADE,
    CONSTRAINT fk_wcv_source_message_family FOREIGN KEY (source_message_id, family_id)
        REFERENCES wechat_conversation_messages(id, family_id),
    CONSTRAINT chk_wcv_revision CHECK (revision >= 1)
);

CREATE INDEX idx_wcv_family_conversation_revision
    ON wechat_candidate_versions (family_id, conversation_id, revision);

CREATE TABLE wechat_task_confirmations (
    id                      BIGSERIAL PRIMARY KEY,
    conversation_id         BIGINT NOT NULL,
    family_id               BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    candidate_version_id    BIGINT NULL,
    candidate_revision      INT NOT NULL,
    candidate_hash          VARCHAR(64) NOT NULL,
    confirmation_message_id BIGINT NULL,
    confirmed_by            BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    idempotency_key         VARCHAR(200) NOT NULL,
    committed_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wtcf_id_family UNIQUE (id, family_id),
    CONSTRAINT uq_wtcf_candidate UNIQUE (candidate_version_id),
    CONSTRAINT uq_wtcf_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uq_wtcf_confirmation_message UNIQUE (confirmation_message_id),
    CONSTRAINT fk_wtcf_conversation_family FOREIGN KEY (conversation_id, family_id)
        REFERENCES wechat_task_conversations(id, family_id),
    CONSTRAINT fk_wtcf_candidate_family FOREIGN KEY (candidate_version_id, family_id)
        REFERENCES wechat_candidate_versions(id, family_id),
    CONSTRAINT fk_wtcf_message_family FOREIGN KEY (confirmation_message_id, family_id)
        REFERENCES wechat_conversation_messages(id, family_id),
    CONSTRAINT chk_wtcf_revision CHECK (candidate_revision >= 1)
);

CREATE INDEX idx_wtcf_family_conversation
    ON wechat_task_confirmations (family_id, conversation_id, committed_at);

CREATE TABLE wechat_confirmation_tasks (
    id                          BIGSERIAL PRIMARY KEY,
    family_id                   BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    confirmation_id             BIGINT NOT NULL,
    task_id                     BIGINT NOT NULL,
    candidate_item_index        INT NOT NULL,
    initial_task_snapshot       JSONB NOT NULL,
    last_observed_fingerprint   VARCHAR(64) NULL,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wct_confirmation_item UNIQUE (confirmation_id, candidate_item_index),
    CONSTRAINT uq_wct_confirmation_task UNIQUE (confirmation_id, task_id),
    CONSTRAINT fk_wct_confirmation_family FOREIGN KEY (confirmation_id, family_id)
        REFERENCES wechat_task_confirmations(id, family_id) ON DELETE CASCADE,
    CONSTRAINT fk_wct_task_family FOREIGN KEY (task_id, family_id)
        REFERENCES tasks(id, family_id) ON DELETE CASCADE,
    CONSTRAINT chk_wct_item_index CHECK (candidate_item_index >= 0)
);

CREATE INDEX idx_wct_family_confirmation
    ON wechat_confirmation_tasks (family_id, confirmation_id);
CREATE INDEX idx_wct_family_task
    ON wechat_confirmation_tasks (family_id, task_id);

CREATE TABLE wechat_message_outbox (
    id                  BIGSERIAL PRIMARY KEY,
    family_id           BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    conversation_id     BIGINT NOT NULL,
    message_id          BIGINT NOT NULL,
    dedupe_key          VARCHAR(200) NOT NULL,
    sequence_no         BIGINT NOT NULL,
    not_before          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts            INT NOT NULL DEFAULT 0,
    next_retry_at       TIMESTAMP WITH TIME ZONE NULL,
    lease_owner         VARCHAR(100) NULL,
    lease_until         TIMESTAMP WITH TIME ZONE NULL,
    wechat_error_code   VARCHAR(32) NULL,
    last_error          VARCHAR(1000) NULL,
    sent_at             TIMESTAMP WITH TIME ZONE NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wmo_message UNIQUE (message_id),
    CONSTRAINT uq_wmo_conversation_dedupe UNIQUE (conversation_id, dedupe_key),
    CONSTRAINT uq_wmo_conversation_sequence UNIQUE (conversation_id, sequence_no),
    CONSTRAINT fk_wmo_conversation_family FOREIGN KEY (conversation_id, family_id)
        REFERENCES wechat_task_conversations(id, family_id) ON DELETE CASCADE,
    CONSTRAINT fk_wmo_message_family FOREIGN KEY (message_id, family_id)
        REFERENCES wechat_conversation_messages(id, family_id) ON DELETE CASCADE,
    CONSTRAINT chk_wmo_status CHECK (
        status IN ('PENDING', 'SENDING', 'SENT', 'CANCELLED', 'FAILED')
    ),
    CONSTRAINT chk_wmo_sequence CHECK (sequence_no >= 1),
    CONSTRAINT chk_wmo_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_wmo_lease_pair CHECK (
        (lease_owner IS NULL AND lease_until IS NULL)
        OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL)
    )
);

CREATE INDEX idx_wmo_family_status_schedule
    ON wechat_message_outbox (family_id, status, not_before, next_retry_at);
CREATE INDEX idx_wmo_dispatch
    ON wechat_message_outbox (status, not_before, next_retry_at);

CREATE TABLE family_task_experiences (
    id                  BIGSERIAL PRIMARY KEY,
    family_id           BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    normalized_key      VARCHAR(200) NOT NULL,
    value_payload       JSONB NOT NULL,
    scope_payload       JSONB NOT NULL,
    status              VARCHAR(12) NOT NULL DEFAULT 'CANDIDATE',
    confidence          NUMERIC(5, 4) NOT NULL DEFAULT 0,
    evidence_count      INT NOT NULL DEFAULT 0,
    version             INT NOT NULL,
    activation_source   VARCHAR(24) NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_fte_id_family UNIQUE (id, family_id),
    CONSTRAINT uq_fte_family_key_version UNIQUE (family_id, normalized_key, version),
    CONSTRAINT chk_fte_status CHECK (status IN ('CANDIDATE', 'ACTIVE', 'DISABLED')),
    CONSTRAINT chk_fte_confidence CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT chk_fte_evidence_count CHECK (evidence_count >= 0),
    CONSTRAINT chk_fte_version CHECK (version >= 1),
    CONSTRAINT chk_fte_activation_source CHECK (
        activation_source IN ('USER_EXPLICIT', 'TASK_EVIDENCE', 'USER_REJECTED')
    )
);

CREATE INDEX idx_fte_family_status_updated
    ON family_task_experiences (family_id, status, updated_at);
CREATE INDEX idx_fte_family_key_version
    ON family_task_experiences (family_id, normalized_key, version);

CREATE TABLE family_task_experience_evidence (
    id                  BIGSERIAL PRIMARY KEY,
    family_id           BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    experience_id       BIGINT NOT NULL,
    source_type         VARCHAR(24) NOT NULL,
    source_id           VARCHAR(200) NOT NULL,
    independence_key    VARCHAR(200) NOT NULL,
    content_hash        VARCHAR(64) NOT NULL,
    observed_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_ftee_independence UNIQUE (family_id, experience_id, independence_key),
    CONSTRAINT fk_ftee_experience_family FOREIGN KEY (experience_id, family_id)
        REFERENCES family_task_experiences(id, family_id) ON DELETE CASCADE,
    CONSTRAINT chk_ftee_source_type CHECK (
        source_type IN ('USER_MESSAGE', 'CONFIRMED_TASK', 'TASK_OBSERVATION')
    )
);

CREATE INDEX idx_ftee_family_experience_time
    ON family_task_experience_evidence (family_id, experience_id, observed_at);
CREATE INDEX idx_ftee_family_source
    ON family_task_experience_evidence (family_id, source_type, source_id);

-- ========================================================================
-- 回滚策略（仅供人工故障处置，Flyway 正向迁移不会执行）：
-- 1. 生产环境先关闭新会话入口、消息 worker、outbox worker 和经验投影器。
-- 2. 只要任一新表已经承载生产数据，就保留全部表，修复后继续消费；不得向下迁移。
-- 3. 仅在确认九张表均未承载生产数据时，才按以下“子表到父表”顺序删除：
--    family_task_experience_evidence -> family_task_experiences ->
--    wechat_confirmation_tasks -> wechat_message_outbox ->
--    wechat_task_confirmations -> wechat_candidate_versions ->
--    wechat_conversation_messages -> wechat_task_conversations ->
--    wechat_chat_context。
-- 4. 最后再删除 uq_tasks_id_family 与 uq_wechat_bind_id_user；旧
--    wechat_inbound_message、agent_decompose_sessions 和 H5 草稿始终保留。
-- ========================================================================

