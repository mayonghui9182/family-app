-- 仅在 V17 是最新版本且两表为空时允许逆向；有用户记录时保留表并回退安全应用。
BEGIN;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM daily_records) OR EXISTS (SELECT 1 FROM daily_settings) THEN
        RAISE EXCEPTION '已有个人数据，禁止删除每日记录；请保留表并回滚安全应用版本';
    END IF;
    IF (SELECT version FROM flyway_schema_history WHERE type = 'SQL' AND success
        ORDER BY installed_rank DESC LIMIT 1) IS DISTINCT FROM '17' THEN
        RAISE EXCEPTION 'V17 不是最新迁移，禁止逆向';
    END IF;
END $$;
DROP TABLE daily_records;
DROP TABLE daily_settings;
DELETE FROM flyway_schema_history WHERE version = '17' AND type = 'SQL';
COMMIT;
