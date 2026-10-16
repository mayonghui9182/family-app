# Tasks: 每日总结 + 对比学习

- [x] Task 0: V7 迁移 + 后端 C-19/C-20/C-21/C-22 端点 + Java 测试（锁归属后端偏差落地）
- [x] Task 1: ❌2 cancelled-draft 信号 + claim/finish 编排 + A-2 手动触发权限（5 用例）
- [x] Task 2: diff_engine 三态算法 + notes 关键词 + 版本保留策略（5 用例）
- [x] Task 3: summarizer 分类循环 60s 超时 + LLM 总结 Prompt + 全量回归 + check_list

## Task 3 落地清单
- db_reader：`_SQL_RECENT_COMPLETED`（json_agg 一次带回子待办+便签，避免 N+1）、
  `_SQL_CANCELLED_AGENT_SIGNAL` 提为模块级常量（权限矩阵审计）、`_as_list` 归一 asyncpg JSON 三态
- prompts/summary_per_category.j2：全中文提示词（StrictUndefined 五变量，含 ❌2 风险提示分支），
  删除 Change 4 占位 summary.j2
- llm_schemas.LLMSummarySuggestion + json_guard.parse_summary（与拆解同栈 L1+L2）
- summarizer.summarize_categories：C-10 分类 × C-11 模板配对 → 近 30 天 ≤10 样本 →
  DiffEngine → 真要落版本才调 LLM（成本纪律）→ C-18 新版本（新 content 仅覆写
  common_steps/notes/count，保留模板其它键）→ C-22 清理 → C-20 收尾（D8 口径）
- 单分类 `asyncio.timeout(settings.summary_timeout_per_category=60)`；超时/异常只记该分类
  中文失败原因，不阻断其它分类；LLM 任意失败降级为空建议（结构化学习不丢）
- B-2 `/api/internal/summary/one-family` 由 stub 转实装（与 22:00/A-2 共用编排与同一把锁，
  trigger_source 归一化，未抢到锁 → completed=false/skipped=true）
- tests/test_summarizer.py 12 例 + tests/test_summary_job.py 扩至 12 例（假件补 C-10/C-18/C-22/
  样本源，Task 1 用例语义不变）
- 计划外缺陷修复 D11（notes target_type 小写）+ D12（分类改走 C-10），各含漂移守卫用例
- 门禁：pytest 85 passed / 覆盖率 95.54% / ruff 0 / mypy app strict 0 / Java 169 tests 0 failures
- check_list.md 10 项全部以「测试名 + 命令行实测数字」填证据，遗留 2 项登记（PG 真机 SQL、
  estimated_finish_at 估计值）


## Task 1 落地清单（含上轮在途未提交部分的收口）
- 后端 C-23 GET /active-families（D5 批源，软删家庭过滤走后端）+ Java 用例
- backend_client：claim_summary_lock / finish_summary_log / append_change_summary /
  get_templates / get_decomposition_templates / get_active_family_ids /
  create_new_template_version / cleanup_template_versions
- db_reader.get_cancelled_agent_signal（status='CANCELLED' AND source='AGENT'，7 天窗口，HAVING ≥5）
- services/summarizer.py：claim → ❌2 信号 → 分类环占位 → C-20 收尾（D8 口径 + 异常必收尾）
- routes/summary_routes.py：A-2 手动触发（AuthVerifier 权限链 → C-19 → 异步后台跑）
- schedulers/summary_job.py：22:00 cron 真注册 + 每家庭批 50 + 单家庭失败隔离
- main.py：装配 summary_routes + startup 起 APScheduler + 46502→400 映射
- tests/test_summary_job.py：10 用例（plan 5 项 + 边界/调度/失败收尾/注册）

## Task 2 落地清单
- app/models/diff_types.py：State(ADD/MODIFY/REMOVE) + SubtaskView + TaskSample +
  DiffItem + NoteMatch + DiffReport（Pydantic）
- app/services/diff_engine.py：title_norm（NFKC + 去空白 + 去 astral + 小写，幂等）、
  levenshtein/similarity、分句短语提取、三态聚合（D9 槽位配对）、
  落笔顺序 MODIFY→REMOVE→ADD、notes 去重追加、中文 change_summary 生成
- 阈值：ADD≥3 / MODIFY≥2 / REMOVE≥3 / notes≥3（常量导出，测试直读）
- 版本保留策略：语义实现 + 证据在 backend C-22（D6）；Python 只做接线
- C-24 GET /templates/{id}/versions（后端版本历史读取）+ Java 3 用例
  （倒序 + limit 截断 + 跨家庭 40404）+ Python 客户端 get_template_versions
- ❌2 幂等（D10）：追加前读最新版当日标记，同日重跑不重复追加
- tests/test_diff_engine.py 15 用例（plan 5 项 + ADD/MODIFY 阈值边界 + 快照纯净 +
  空样本 + title_norm 参数化 + C-18/C-22/C-24 接线）
- 清理：删除 Change 4 遗留的 diff_engine stub compare/DiffResult（test_stubs 同步）
