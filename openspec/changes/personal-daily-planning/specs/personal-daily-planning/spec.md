## Purpose

为个人每天的生活与工作提供统一计划和可追溯复盘，通过明确时间、权限过滤和持久化快照让用户判断当天重点及未完成原因，避免把模板学习报告误当作个人复盘。

## ADDED Requirements

### Requirement: Daily plan
系统 SHALL 在 Asia/Shanghai 08:30 生成启用用户的今日计划，聚合本人工作、本人家庭任务和可访问日程。草稿不得作为已确认待办；重点最多三项，用户可调整确认。

#### Scenario: Morning generation
- **WHEN** 到达 08:30
- **THEN** 保存任务来源、权限范围、生成时间与初始快照，不修改实际任务

### Requirement: Review against plan
系统 SHALL 在 21:00 对照早晨计划生成复盘，标注实际截止时间，允许记录阻碍和明日建议。

#### Scenario: No morning plan
- **WHEN** 没有早晨快照
- **THEN** 显示无计划基准，不伪造完成率或完成时间

### Requirement: Idempotency and concurrency
系统 SHALL 以用户、日期、类型防重，当日停机恢复后补跑；编辑和确认 MUST 校验版本。

#### Scenario: Two workers
- **WHEN** 两个实例同时生成同一记录
- **THEN** 只有一份有效记录

#### Scenario: Stale edit
- **WHEN** 提交旧版本调整
- **THEN** 返回冲突并要求刷新

### Requirement: Privacy survives snapshots
系统 SHALL 每次读取历史记录重新校验家庭成员和工作归属，无权数据只显示不可访问标记。

#### Scenario: Member leaves family
- **WHEN** 用户退出家庭后读取旧计划
- **THEN** 不显示该家庭任务标题、快照或复盘内容

### Requirement: Accessible daily entry
系统 SHALL 提供大字号、高对比、可键盘操作的我的一天页面，明确生成、补跑、来源失败和确认状态。

#### Scenario: Work service unavailable
- **WHEN** 工作数据不可读取
- **THEN** 明确提示未完整加载且可重试，不显示为没有任务
