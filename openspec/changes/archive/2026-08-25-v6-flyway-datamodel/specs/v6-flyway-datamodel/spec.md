# Delta Spec: v6-flyway-datamodel

> 变更类型：ADDED（7 张新表能力域 agent-datamodel）+ MODIFIED（daily-task-mgmt 的 DRAFT 可见性）

## ADDED Requirements

### Requirement: 子待办表（subtasks）
系统 SHALL 提供 subtasks 表存储任务拆解产物，每行 MUST 携带 `family_id BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE`、`parent_task_id` 外键、5 值状态 CHECK（chk_subtasks_status）及 `is_ai_generated` 标记。

#### Scenario: AI 拆解产物落库
- **WHEN** 为 family=1 的 task 创建一条 is_ai_generated=true 的子待办
- **THEN** 行成功插入且 family_id/parent_task_id 约束生效
- **AND** 违反任一外键或 CHECK 的插入被数据库拒绝

### Requirement: 注意事项表（notes 多态挂载）
notes 表 SHALL 以 `target_type ∈ ('task','subtask')` + `target_id` 的多态引用挂载注意事项，并 MUST 携带 family_id 隔离。

#### Scenario: 多态引用约束
- **WHEN** 插入 target_type='comment' 的行
- **THEN** 被 chk_notes_target_type 拒绝

### Requirement: 规则模板及版本历史
rule_templates SHALL 支持 `type ∈ ('decomposition','routine')`、JSONB conditions/content、`(family_id, name)` 唯一约束（uk_rt_family_name_active）；rule_template_versions SHALL 记录 `(template_id, version)` 唯一的快照，并支持 parent_version_id 血缘。

#### Scenario: 同家庭同名模板唯一
- **WHEN** family=1 再次插入名为「周末大扫除」的活动模板
- **THEN** 被 uk_rt_family_name_active 拒绝

#### Scenario: 版本快照不可重复
- **WHEN** 同一 template 插入重复 version 号
- **THEN** 被 uk_rtv_template_version 拒绝

### Requirement: 调度与依赖触发表
task_schedules SHALL 按 `(family_id, trigger_date, is_executed)` 支持一次性定时；task_triggers SHALL 支持 `trigger_type='on_complete'` 的源任务完成触发，携带 JSONB target_template。

#### Scenario: 触发类型受约束
- **WHEN** 插入 trigger_type='cron' 的行
- **THEN** 被 chk_tt_type 拒绝

### Requirement: 总结执行日志与分布式锁键
summary_execution_log SHALL 以 `(family_id, run_at::date)` 表达式唯一索引（uk_sel_family_run_date）作为每日总结的分布式锁，status ∈ ('success','failed')，trigger_source ∈ ('auto','manual')。
注：表达式唯一索引属 PG 专有，由 afterMigrate__agent_reader.sql 回调创建；H2 环境以普通索引替代。

#### Scenario: 同家庭同日仅一次总结
- **WHEN** family=1 同一天第二次 Insert 锁行
- **THEN** 唯一冲突，调度方据此 skip

### Requirement: agent_reader 最小权限只读角色（PG 专有）
PostgreSQL 环境 SHALL 通过 afterMigrate 回调条件创建 `agent_reader` 角色，仅授予白名单列的 SELECT（排除 password_hash / access_token / deleted_at 等敏感列）、7 新表全列 SELECT 及所需序列 USAGE。H2 环境 SHALL 忽略该回调。

#### Scenario: 敏感列拒绝
- **WHEN** 以 agent_reader 查询 users.password_hash
- **THEN** permission denied

#### Scenario: 白名单列允许
- **WHEN** 以 agent_reader 查询 tasks.id/title/status
- **THEN** 返回结果

## MODIFIED Requirements

### Requirement: 过期 DRAFT 默认隐藏（❌1 补项，归属 daily-task-mgmt 域）
任务列表查询 SHALL 默认拼接谓词 `(status != 'DRAFT') OR (status = 'DRAFT' AND expires_at > CURRENT_TIMESTAMP)`；显式 `?status=DRAFT` 时 SHALL 仅返回未过期 DRAFT。软删除任务（deleted_at IS NOT NULL）SHALL 永远不出现在列表。

#### Scenario: 过期 DRAFT 自动隐藏
- **WHEN** 存在 2 小时前过期的 DRAFT 与正常 PENDING 各一条并查询列表
- **THEN** 仅返回 PENDING 一条

#### Scenario: 未过期 DRAFT 正常返回
- **WHEN** 存在 12 小时后过期的 DRAFT 并查询列表
- **THEN** 该 DRAFT 出现在结果中

#### Scenario: 软删除任务不泄漏
- **WHEN** 已软删除（deleted_at 非空）的任务存在于库中并查询列表
- **THEN** 不出现在结果中

### Requirement: tasks.status 防御纵深
tasks 表 SHALL 统一名为 chk_tasks_status 的 CHECK 约束，限定 status ∈ ('DRAFT','PENDING','IN_PROGRESS','COMPLETED','CANCELLED')。

#### Scenario: 非法状态拒绝
- **WHEN** 直接 SQL 插入 status='ARCHIVED' 的行
- **THEN** 被 chk_tasks_status 拒绝
