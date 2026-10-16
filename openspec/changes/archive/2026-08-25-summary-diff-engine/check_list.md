# Check List: summary-diff-engine

| # | 验证项 | 结果 | 证据（本轮实测） |
|---|---|---|---|
| 1 | ❌2 ≥5 次取消 → change_summary 含中文警告（C-21 追加） | ✅ | Python `tests/test_summary_job.py::test_cancelled_signal_appends_chinese_warning`（断言 ⚠️ / 「近 7 天」/「取消 AI 提案 7 次」/三条自查 + 同日幂等 `test_cancelled_signal_is_idempotent_within_same_day` + 无模板跳过 + 读史失败仍追加 `test_version_history_read_failure_still_appends_warning`）；阈值口径由 `FakeSummaryDb` 强制断言 `(window_days, threshold)==(7,5)`；后端 `InternalAgentSummaryLockTest.C21_append_ok_concatsWithNewline` / `C21_append_wrongFamily_40404` / `C21_append_noVersion_46503` |
| 2 | 分布式锁：PG 唯一冲突 skip；force 同日重跑允许 | ✅ | 锁归属后端（D1）：`C19_claim_success_returnsAcquiredTrueWithRunningRow`（V7 后 running 可插，H2 全量 Flyway 即迁移证据）、`C19_claim_force_deletesSameDayThenReclaims`、`C19_claim_badTriggerSource_40099`、`C20_finish_twice_40011`、`C20_finish_wrongFamily_40407`；`uk_sel_family_run_date` 表达式唯一索引真机行为在 Change 1 已验收；agent-service 侧 `test_lock_contention_hundred_concurrent_only_one_runs`（100 并发 → 1 跑 99 skip、收尾仅 1 次）+ `test_a2_same_day_conflict_and_force`（force 序列 `[(1,'manual',False),(1,'manual',False),(1,'manual',True)]`） |
| 3 | diff ADD≥3 / MODIFY≥2 / REMOVE≥3 / notes≥3 阈值正确 | ✅ | `tests/test_diff_engine.py::test_add_recurrence_ge3_appends_new_step` / `test_add_recurrence_below_threshold_does_not_emit` / `test_modify_inplace_rewrite_ge2_replaces_step` / `test_modify_below_threshold_keeps_original` / `test_remove_deleted_ge3_drops_step` / `test_notes_phrase_ge3_appends_template_notes`；阈值常量 `ADD_THRESHOLD=3 / MODIFY_THRESHOLD=2 / REMOVE_THRESHOLD=3 / NOTE_THRESHOLD=3` 被测试直读（改一个即红） |
| 4 | A-2 CHILD → 40302 中文；OWNER/ADULT 返回 trigger_id 异步 | ✅ | `tests/test_summary_job.py::test_a2_child_role_rejected_40302`（403 + code 40302 + 「孩子成员」+ claim_calls 为空）；`test_a2_same_day_conflict_and_force`（200 + `{trigger_id, estimated_finish_at, message}` 严格三键 + 后台任务完成后才收尾）；`test_a2_requires_token_and_family`（40103 / 40099）；路径前缀偏差记于 design D7 |
| 5 | 单分类 60s 超时 → failed_categories 记录、不阻断其它分类 | ✅ | `tests/test_summarizer.py::test_single_category_timeout_does_not_block_others`（预算压至 0.2s 等价验证：家务超时记失败、育儿照常落版本）；生产值 `test_timeout_defaults_to_settings`（`settings.summary_timeout_per_category==60`）；失败原因含「单分类总结超时」「不阻断其它分类」；另有 `test_category_read_error_is_isolated_from_others`（非超时异常同样隔离且不消耗 LLM 预算） |
| 6 | 版本保留：is_important 永久 / 普通 30 天 / 每模板最少 3 条 | ✅ | 语义实现落在后端 C-22（agent_reader 无 DELETE 权限）：`C22_cleanup_keepsImportantAndYoungest3_deletesOldRest`（30 天外 + is_important=false + 非最新 3 条 → 删 2 保 6/5/4/1）、`C22_cleanup_otherFamilyUntouched`；编排接线 `test_run_family_cleans_versions_and_finishes_log`（每家庭收尾前恰调 1 次）+ `test_c22_cleanup_called_on_backend_and_parses_deleted` |
| 7 | is_ai_generated=true 才进 AI snapshot | ✅ | `test_family_added_dup_of_ai_step_never_enters_add_and_keeps_snapshot_pure`（家人补同名步骤 → items 为空、快照不被污染）+ `test_modify_inplace_rewrite_ge2_replaces_step`（仅 AI 行参与改写判定）+ `test_add_recurrence_ge3_appends_new_step`（ADD 只认 is_ai_generated=false）；`_SQL_RECENT_COMPLETED` 逐行回传 `is_ai_generated` 并在 `Summarizer._to_samples` 保真透传 |
| 8 | 新版本 change_summary 全中文；写操作 100% backend_client | ✅ | `test_category_loop_emits_learned_version_with_tips` 断言「自动总结·家人行为学习」「阈值」「AI 辅助建议：…」逐条中文 + 复现次数；结构审计：`grep -rn 'INSERT \|UPDATE \|DELETE \|executemany\|session.commit' agent-service/app --include='*.py'` → 0 命中；`grep -rn sqlalchemy agent-service/app/{services,routes}` → 0 命中（服务/路由层无直连写库路径，写全走 `backend_client`）；LLM 建议只进 change_summary 不改写 steps（`mark_important` 之外无副作用） |
| 9 | ruff 0 / mypy strict 0 / pytest 覆盖率 ≥ 80% | ✅ | `ruff check .` → All checks passed!；`mypy app`（strict）→ Success: no issues found in 31 source files；`pytest -q` → **85 passed**，coverage **95.54%**（门槛 80%；新模块 summarizer 91%+ / diff_engine 91% / db_reader 归一逻辑补齐） |
| 10 | Java 回归基线保持（152 + 新增全绿） | ✅ | `./gradlew test --console=plain` → XML 聚合 **tests=169 failures=0 errors=0 skipped=0**（基线 152 + Change 5 新增 17，其中 `InternalAgentSummaryLockTest` 15 例覆盖 C-19/C-20/C-21/C-22/C-23/C-24 + V7 running 状态）；顺带修复两处跨服务契约缺陷并补测：notes `target_type` 必须小写（D11）、分类清单改走 C-10（D12） |

## 附加：本轮发现并修复的两处真机缺陷（原计划外，证据同表 #10）

1. **C-8 `target_type` 大小写**（design.md D11）：`decomposer` 传 `"TASK"`，后端 `InternalAgentController:241` 与 V6
   `chk_notes_target_type` 只认小写 `task`/`subtask` → 任何带 notes 的 AI 拆解在真机必 400 → 回滚 → 56003。
   修：编排层改小写 + 连接器归一（`_note_target`，非法值不发请求）+ wire 层断言真实报文
   `'"target_type":"task"'`（`test_backend_batch_note_cancel_categories`）+ 新增
   `test_note_target_type_rejects_unknown_value` + 编排层 `test_agent_notes_use_lowercase_target_type`。
2. **`categories.deleted_at` 越权读取**（design.md D12）：`DbReader.get_categories` 的 SQL 以
   `deleted_at IS NULL` 过滤软删，但 afterMigrate 对 categories 的列授权排除 `deleted_at` →
   配置 `DB_AGENT_READER_URL` 后拆解 Step 2 必触发 42501。修：删除该方法，分类唯一来源改为后端 C-10
   （与 D5 families 同源）；漂移守卫 `test_db_reader_respects_agent_reader_grant_matrix`
   （断言 `_SQL_*` 常量不含 `deleted_at` 且 `DbReader` 不再有 `get_categories`）。

## 遗留（不阻断本 change 验收，已登记 handoff）

- 学习样本 SQL（`_SQL_RECENT_COMPLETED` / `_SQL_CANCELLED_AGENT_SIGNAL`）为 PG 专有
  （`make_interval` / `json_agg`），H2 测试不覆盖 → 单元层以假件锁定行为契约，真机语义待
  Change 7 docker-compose E2E（届时须验：软删任务可能进入样本使复现计数偏大）。
- A-2 的 `estimated_finish_at` 为固定 +15min 估计值（Q45 产品口径），非真实进度；Change 6 若需
  真实进度，走 C-20 的 `summary_execution_log` 读取。
