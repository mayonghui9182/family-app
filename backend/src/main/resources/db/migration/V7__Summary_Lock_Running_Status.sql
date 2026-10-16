-- ========================================================================
-- V7 (Change 5): 每日总结分布式锁 —— summary_execution_log.status 放宽
-- ========================================================================
-- Change 1 的 V6 仅允许 ('success','failed')；Change 5 抢锁语义需要
-- INSERT status='running' 占位，依赖 uk_sel_family_run_date（afterMigrate
-- 表达式唯一索引）命中冲突即视为当日已跑、skip。
-- H2 兼容：ALTER TABLE ... DROP/ADD CONSTRAINT 均为 H2 支持语法。
-- ========================================================================

ALTER TABLE summary_execution_log DROP CONSTRAINT IF EXISTS chk_sel_status;
ALTER TABLE summary_execution_log ADD CONSTRAINT chk_sel_status
    CHECK (status IN ('running','success','failed'));
