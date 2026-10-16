# task-planner/template-learning Delta Specification

## Purpose

decomposition 规则模板与自学习模块：模板内容（常见步骤、预期子待办数、注意事项）与启停、版本历史与保留策略、用户手动编辑优先；每日 22:00 定时（或手动触发）的总结任务按分类对比「AI 原始生成子待办 vs 用户修改后子待办」的 diff，同一调整模式出现 2 次以上才生成带 reason 的模板更新（add_step / remove_step / update_estimated_count / update_notes），写回模板并留下版本记录与执行日志，含 60 秒分类超时、1/5/30 分钟退避重试与过期 draft 清理。

## ADDED Requirements

### Requirement: Decomposition rule template persistence and retrieval

The system SHALL persist rule templates with identity, `type` (one of `decomposition`, `routine`), `name`, `category_id`, `conditions` (null for decomposition templates), `content` JSONB, `is_active` (default true), `version` and `parent_version_id`. For `type=decomposition` the `content` SHALL hold `common_steps` (array of step strings), `estimated_subtask_count` (integer) and `notes` (string). The system SHALL expose `GET /api/templates` supporting the combinable filters `category` and `type`, returning the unified JSON envelope. This capability SHALL implement behavior for `type=decomposition` only; `routine` templates may be stored but SHALL NOT be evaluated or generated in this release.

#### Scenario: Templates can be filtered by category and type

- **WHEN** two decomposition templates and one routine template exist across two categories and a client calls `GET /api/templates?type=decomposition&category=<uuid>`
- **THEN** `data` contains only the decomposition templates of that category

#### Scenario: Template content follows the decomposition shape

- **WHEN** a decomposition template is created with steps 确定主题/制作PPT/彩排 and expected count 4
- **THEN** the stored `content` equals `{"common_steps":["确定主题","制作PPT","彩排"],"estimated_subtask_count":4,"notes":""}` and `version` is 1 and `is_active` is true

#### Scenario: Routine templates are stored but not acted upon

- **WHEN** a `routine` template exists in the database
- **THEN** no scheduler in this release evaluates its `conditions`, and it is never returned by the agent's decomposition template matching

### Requirement: Template changes create version history with bounded retention

Every change to a decomposition template's `content` SHALL increment `rule_templates.version` and SHALL insert a `rule_template_versions` row holding `template_id`, the new `version`, a full `content` snapshot, `change_summary`, `is_important` (default false) and `parent_version_id` pointing at the previous version. `GET /api/templates/{id}/versions` SHALL return the versions ordered by `version` descending. The system SHALL target at most 20 version rows per template by deleting the oldest non-important rows. Versions with `is_important=true` and the current version SHALL never be auto-deleted; the count MAY exceed 20 when all remaining rows are protected.

#### Scenario: Applying an update snapshots the new version

- **WHEN** a template at version 3 receives an update
- **THEN** `rule_templates.version` becomes 4 and a version row with `version` 4 exists whose `content` equals the post-update content and whose `parent_version_id` references the version 3 row

#### Scenario: Version history is listed newest first

- **WHEN** a template has versions 1 through 4 and a client calls `GET /api/templates/{id}/versions`
- **THEN** `data` lists version 4 first and version 1 last

#### Scenario: Retention deletes the oldest versions beyond twenty

- **WHEN** a template accumulates a 21st version and none of its versions is marked important
- **THEN** the oldest version row is deleted and exactly 20 rows remain

#### Scenario: Important versions survive retention

- **WHEN** a template accumulates a 21st version while its version 1 row has `is_important=true`
- **THEN** the version 1 row still exists and the oldest non-important row is the one deleted

### Requirement: Users can manually edit templates and mark versions important

Users SHALL be able to edit a decomposition template's `common_steps`, `estimated_subtask_count` and `notes`, to toggle `is_active`, and to set `is_important` on a version. A manual content edit SHALL create a new version whose `change_summary` is "用户手动编辑", and the system SHALL record the manual edit time so that a same-day summary run can detect it. A deactivated template (`is_active=false`) SHALL NOT be offered to the agent as a matching candidate, while its version history SHALL be preserved and it SHALL be reactivatable.

#### Scenario: Manual edit is recorded with its own change summary

- **WHEN** a user edits a template's `common_steps` through the template API
- **THEN** a new version row exists with `change_summary` equal to "用户手动编辑"

#### Scenario: Deactivated template is excluded from agent matching

- **WHEN** a template is updated to `is_active=false` and the agent requests decomposition templates for that category
- **THEN** the returned candidate list does not contain that template

#### Scenario: Marking a version important protects it

- **WHEN** a user sets `is_important=true` on a version row
- **THEN** subsequent retention passes leave that row in place

### Requirement: Daily summary runs at 22:00 and can be triggered manually

agent-service SHALL execute the daily summary every day at 22:00 server-local time through its scheduler, and SHALL expose `POST /api/summary/trigger` for manual execution with exactly the same data scope and processing steps. When a summary has already run for the current day, the manual trigger SHALL refuse to re-run unless the request carries an explicit force flag, and SHALL respond with a conflict status indicating that the day was already summarized. Each run SHALL write exactly one `summary_execution_log` record with `trigger_source` equal to `auto` for scheduled runs and `manual` for triggered runs.

#### Scenario: Scheduled run logs an auto trigger

- **WHEN** the scheduler fires the 22:00 summary
- **THEN** a `summary_execution_log` row exists with `trigger_source` equal to `"auto"` and `run_at` on the current day

#### Scenario: Manual trigger logs a manual trigger

- **WHEN** a client calls `POST /api/summary/trigger` on a day that has not been summarized yet
- **THEN** the summary executes and the logged row has `trigger_source` equal to `"manual"`

#### Scenario: Re-running the same day requires force

- **WHEN** a client calls `POST /api/summary/trigger` after a successful run on the same day without the force flag
- **THEN** the response status is 409 and the body indicates that the day was already summarized and asks for confirmation

### Requirement: Summary data window and per-category sampling

The summary SHALL consider tasks created, completed or modified between 00:00 of the current day and the execution moment, grouped by each task's **current** `category_id`, so that a task recategorized during the day contributes to the new category's template. Per category it SHALL select at most 10 tasks that carry user modifications, preferring the largest modification magnitude. For every selected task the agent SHALL obtain, through MCP `get_task_detail`, both the immutable AI-original proposal snapshot (`ai_snapshot`) and the current subtask set, and SHALL compute the diff: steps the user deleted (over-decomposition) and steps the user added (missing decomposition). Tasks without user modifications SHALL only be inspected for newly emerging patterns and SHALL NOT by themselves change a template.

#### Scenario: Only same-day activity is considered

- **WHEN** a task was completed yesterday and another was completed today before the run
- **THEN** only today's task appears in the summary input

#### Scenario: Recategorized task counts toward the new category

- **WHEN** a task created under category A is moved to category B and modified the same day
- **THEN** the summary processes it under category B

#### Scenario: Sampling is capped at ten tasks per category

- **WHEN** 15 modified tasks exist in one category
- **THEN** the summary input for that category contains the 10 tasks with the largest modification magnitude

#### Scenario: Diff distinguishes deleted from added steps

- **WHEN** the AI generated 确定主题/制作PPT/彩排/物料准备/打印材料 and the user's final set replaces 打印材料 with 邀请媒体
- **THEN** the computed diff records a deleted step "打印材料" and an added step "邀请媒体"

### Requirement: Contrastive learning requires a repeated pattern and reasoned updates

For each sampled category per attempt the agent SHALL make exactly one LLM call, processing categories serially, with a 60 second timeout per category, producing JSON of the shape `{"template_updates":[...],"new_notes":"..."}`. When a category contains multiple template groups, the response SHALL contain `template_results` entries with `target_key` and `template_updates`, validated against the corresponding group only. Every entry of `template_updates` SHALL use one of the actions `add_step` (fields `step`, `reason`), `remove_step` (fields `step`, `reason`), `update_estimated_count` (fields `value`, `old_value`, `reason`) or `update_notes` (fields `value`, `reason`). An adjustment pattern SHALL only produce an update when it was observed in 2 or more tasks; a single incidental adjustment SHALL NOT modify the template. The application SHALL verify additions/removals against the normalized diff of at least two distinct task IDs, independently of model claims. Count and note updates SHALL reference at least two valid modified tasks using `evidence_task_ids`; count updates SHALL also match their observed current counts and the template old value. `new_notes` SHALL NOT directly modify stored notes. An update entry with an unknown action, or missing its required fields, SHALL be skipped without aborting the run.

#### Scenario: A twice-repeated addition becomes add_step

- **WHEN** two tasks of the same category both received a user-added step "邀请媒体"
- **THEN** `template_updates` contains `{"action":"add_step","step":"邀请媒体","reason":"..."}` with a non-empty reason

#### Scenario: A single incidental change does not update the template

- **WHEN** exactly one task shows a user-deleted step
- **THEN** `template_updates` contains no `remove_step` entry for that step and the template content is unchanged

#### Scenario: Estimated subtask count update carries old and new values

- **WHEN** the learned pattern indicates the category needs 6 subtasks instead of 4
- **THEN** `template_updates` contains an `update_estimated_count` entry with `value` 6 and `old_value` 4

#### Scenario: Unknown action is skipped

- **WHEN** the model returns an entry with `action` equal to `"rewrite_template"`
- **THEN** that entry is ignored, the remaining valid entries are applied and the run still completes

### Requirement: Learned updates are written back through MCP with a version trail

task-service SHALL expose an MCP tool for writing template content back (`update_rule_template`), and the agent SHALL apply each run's updates through MCP immediately after that category is processed rather than waiting for all categories. Applying updates SHALL append `add_step` steps to the end of `common_steps`, remove `remove_step` steps, replace `estimated_subtask_count` and `notes` as directed, increment the template `version` and write a version row whose `change_summary` lists the applied actions with their reasons. When a category with modification evidence has no decomposition template yet, the summary SHALL create one from the learned steps with `change_summary` "每日总结自动创建". A category whose processing fails SHALL NOT prevent other categories from being updated.

#### Scenario: Applied updates are traceable in the version history

- **WHEN** the summary applies `add_step 邀请媒体` and `remove_step 打印材料` to a template
- **THEN** the template's `common_steps` contains 邀请媒体 and no longer contains 打印材料, and the newest version row's `change_summary` mentions both actions with their reasons

#### Scenario: Category without a template accumulates a new one

- **WHEN** the summary finds repeated patterns in a category that has no decomposition template
- **THEN** a new decomposition template exists for that category with `version` 1 and `change_summary` equal to "每日总结自动创建"

#### Scenario: One failing category does not block the others

- **WHEN** the LLM call for category A times out while category B is processed afterwards
- **THEN** category B's template is still updated and the run finishes

### Requirement: Same-day manual edits take precedence over learned updates

If a decomposition template was manually edited on the day of the summary run, the summary SHALL only append new steps to the end of `common_steps` and SHALL NOT overwrite, reorder or remove user-edited steps, `estimated_subtask_count` or `notes`. The resulting version row's `change_summary` SHALL be marked "自动补充（用户已手动编辑，未覆盖）".

#### Scenario: Learned removal is suppressed after a manual edit

- **WHEN** the template was manually edited at 14:00 and the 22:00 summary produced `remove_step 打印材料` for it
- **THEN** 打印材料 is still present in `common_steps`, only `add_step` entries were appended, and the new version's `change_summary` equals "自动补充（用户已手动编辑，未覆盖）"

### Requirement: Summary failures retry with backoff and never notify

A run-level failure SHALL be retried with exponential backoff at 1 minute, then 5 minutes, then 30 minutes, for at most 3 retries after the initial attempt (4 attempts total). If all attempts fail, the day SHALL be skipped, the failure SHALL be recorded with status `failed` and its `error_message`, and no notification SHALL be pushed. If three consecutive days fail, the next successful run SHALL summarize the accumulated data window of those days instead of only the current day.

#### Scenario: Transient failure recovers on retry

- **WHEN** the first attempt fails and the retry 1 minute later succeeds
- **THEN** a `summary_execution_log` row with status `success` exists for that day

#### Scenario: Exhausted retries record a failed run

- **WHEN** the initial attempt and all three retries fail
- **THEN** a `summary_execution_log` row exists with status `failed` and a non-empty `error_message`, and no further attempt is made that day

#### Scenario: Three consecutive failed days widen the next window

- **WHEN** the last three daily runs are logged as `failed` and the next run succeeds
- **THEN** the successful run's data window covers those three days plus the current day

### Requirement: Summary run expires stale drafts and records its execution log

Each summary run SHALL mark every draft task whose `expires_at` is earlier than the execution time as `cancelled`, retaining the record for learning, and SHALL then persist one `summary_execution_log` row through the MCP tool `log_summary_execution` containing `run_at`, `status` (`success` or `failed`), `error_message`, `categories_updated` (names of the categories whose template actually changed) and `trigger_source`. The execution log SHALL be readable through task-service so that the last successful update time of a template can be reported.

#### Scenario: Expired drafts are cancelled by the run

- **WHEN** a draft task's `expires_at` passed two hours before the run
- **THEN** after the run its status is `cancelled` and it is still retrievable via `GET /api/tasks?status=cancelled`

#### Scenario: Fresh drafts are left untouched

- **WHEN** a draft task's `expires_at` is still in the future
- **THEN** after the run its status is still `draft`

#### Scenario: Execution log records the updated categories

- **WHEN** the run updated templates of categories 市场推广 and 工作汇报
- **THEN** the logged row has status `success` and `categories_updated` equals `["市场推广","工作汇报"]`

#### Scenario: Failed run logs the error without updated categories

- **WHEN** the run fails on every attempt
- **THEN** the logged row has status `failed`, a non-empty `error_message` and a `categories_updated` array containing only groups actually committed before failure (empty if no group succeeded)

### Requirement: Learning evidence and template ownership remain stable

The system SHALL store an immutable original proposal snapshot including source template identity/version and original subtasks. Legacy tasks without a snapshot, unconfirmed drafts and cancelled tasks SHALL NOT automatically change templates. Within each category's ten-task sample, tasks SHALL be partitioned by their original template. Recategorized tasks SHALL contribute to a cold-start group in the current category. Cold-start template creation SHALL use a unique category learning key and SHALL respect deactivation. Reads SHALL paginate through the complete task window and template list; a template read failure SHALL NOT be treated as absence.

#### Scenario: AI omission is not a user deletion

- **WHEN** the template contains 打印材料 but the original AI proposal never contained it
- **THEN** the summary does not report a user deletion of 打印材料

#### Scenario: Two templates do not exchange evidence

- **WHEN** two templates exist in a category and tasks reference the second template
- **THEN** the tasks can update only the second template rather than the first returned row

#### Scenario: Cold-start template is not duplicated

- **WHEN** a summary already created the category's cold-start template and another summary processes cold-start evidence
- **THEN** the existing active template is updated rather than creating another, and a deactivated one is left untouched

### Requirement: Concurrent learning preserves user edits and honest run status

Automatic template updates SHALL carry expected_version; the data service SHALL lock the current template and reject a version mismatch. Template version numbers SHALL be unique per template. Manual edit time SHALL survive version retention. Scheduled and manual runs SHALL share one execution mutex in the supported single-worker deployment. Force SHALL NOT bypass an in-progress run. Any failed category SHALL mark the attempt unsuccessful while allowing others to finish; retries SHALL skip groups already completed in the run. A run SHALL NOT report success when its execution log cannot be persisted. Failed days SHALL be counted by local calendar dates, widening over the entire consecutive failure streak once at least three days failed.

#### Scenario: Manual update during model latency wins

- **WHEN** a user saves version 2 while a summary was computed from version 1
- **THEN** the version 1 write is rejected and version 2 content remains intact

#### Scenario: All categories fail

- **WHEN** every category's model call fails through all retries
- **THEN** the execution is failed with a non-empty error, and it does not block ordinary manual retry as a success

#### Scenario: Concurrent force trigger is rejected

- **WHEN** a scheduled or manual summary is in progress and another request sets force=true
- **THEN** the second request receives 409 and does not start another learning run

#### Scenario: Three same-day failures are one failed day

- **WHEN** three failed execution logs all belong to the current calendar date
- **THEN** the system does not infer a three-day failure streak
