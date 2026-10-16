-- 仅停止每日写入后的维护窗口使用；有数据时保留结构并退回安全应用。
-- 不允许级联删除，不触碰 public 的迁移历史。
BEGIN;
LOCK TABLE daily_app.daily_records, daily_app.daily_settings,
    daily_app.flyway_schema_history IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM daily_app.daily_records)
        OR EXISTS (SELECT 1 FROM daily_app.daily_settings) THEN
        RAISE EXCEPTION '已有个人数据，禁止删除每日结构';
    END IF;
    IF (SELECT count(*) FROM daily_app.flyway_schema_history
        WHERE type = 'SQL' AND success AND version = '1') <> 1
        OR EXISTS (SELECT 1 FROM daily_app.flyway_schema_history
            WHERE type <> 'SCHEMA' AND (type <> 'SQL' OR version IS DISTINCT FROM '1' OR NOT success)) THEN
        RAISE EXCEPTION '每日迁移历史不符合仅 V1 的回退条件';
    END IF;
END $$;
DROP TABLE daily_app.daily_records;
DROP TABLE daily_app.daily_settings;
DROP TABLE daily_app.flyway_schema_history;
DROP SCHEMA daily_app;
COMMIT;
