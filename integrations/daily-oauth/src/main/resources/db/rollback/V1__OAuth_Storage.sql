-- 仅独立授权 Schema 的最新 V1 且授权/同意为空时允许逆向；生产优先停入口保留数据。
BEGIN;
DO $$
BEGIN
    IF EXISTS(SELECT 1 FROM oauth2_authorization) OR EXISTS(SELECT 1 FROM oauth2_authorization_consent) THEN
        RAISE EXCEPTION '授权数据非空，拒绝逆向';
    END IF;
    IF (SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1) IS DISTINCT FROM '1' THEN
        RAISE EXCEPTION 'V1 不是最新迁移，拒绝逆向';
    END IF;
END $$;
DROP TABLE oauth2_authorization_consent;
DROP TABLE oauth2_authorization;
DROP TABLE oauth2_registered_client;
DELETE FROM flyway_schema_history WHERE version='1' AND success;
COMMIT;
