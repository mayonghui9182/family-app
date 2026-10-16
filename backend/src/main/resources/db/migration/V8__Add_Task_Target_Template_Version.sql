-- C-31 pick：Task 表增加 target_template_version_id 列
-- 用于 AI 拆解时锁定选中的模板版本
ALTER TABLE tasks ADD COLUMN target_template_version_id BIGINT;
