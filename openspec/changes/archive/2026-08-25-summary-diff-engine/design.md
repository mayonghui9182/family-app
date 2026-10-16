# Design: 每日总结 + 对比学习

## D1：分布式锁归属后端（偏差记录）
plan 原文让 summary_job 直接 INSERT summary_execution_log 抢锁。实际约束：
1. agent_reader 角色对 summary_execution_log 仅 SELECT（afterMigrate__agent_reader.sql 行 63）；
2. V6 CHECK 约束 status IN ('success','failed')，无 'running'；
3. Change 4 冻结决策「写操作 100% 走 backend_client」。
故：V7 迁移放宽 CHECK 至 ('running','success','failed')；后端新增
C-19 POST /summary-log/claim（INSERT 'running'，UniqueViolation→acquired=false；
force=true 先删同 UTC 日再插）+ C-20 PUT /summary-log/{id}（running→success/failed）。
uk_sel_family_run_date 表达式唯一索引（Change 1 afterMigrate 已建）承担并发互斥。
H2 测试无表达式索引 → 冲突路径用 Mockito 单测模拟 DataIntegrityViolationException。
Python 侧并发证据链：锁互斥真机验证在 Java/PG（C-19 冲突返回 acquired=false），
agent-service 单测锁定「acquired=false ⇒ 绝不执行总结主体、绝不写收尾」的行为契约
（test_lock_contention_hundred_concurrent_only_one_runs：100 并发仅 1 跑）。

## D2：❌2 change_summary 追加走新端点（偏差记录）
plan 写「通过 C-18 更新」，但 C-18 语义是建新版本。❌2 需要追加文本到最新版
change_summary，故新增 C-21 POST /templates/{id}/change-summary?family_id=
（body {append}），只改最新版 change_summary，不产生新版本。模板无版本行→46503。

## D3：复现计数语义（无新表）
plan 的 recurrence_count 不引入新表，从现有数据计算：
- ADD/REMOVE/MODIFY：同分类 COMPLETED 任务的子待办中，title_norm 相同且
  is_ai_generated 符合状态语义的行数（ADD 计 is_ai_generated=false 的家人新增行）；
- notes 短语：同分类 COMPLETED 任务便签中含该短语的任务数。
首次 diff 时复现计数来自历史累积，阈值语义不变。
落地形态：由 db_reader.get_recent_completed 一次取回近期样本（任务+子待办+便签），
DiffEngine 在内存按「样本数」聚合复现次数（每样本对同一短语/同一 norm 只计 1 次），
等价于「同分类近 30 天内复现 N 次」，且避免 N 次 SQL 往返。

## D4：LLM 沿用 openai SDK（对齐 Change 4 定版）
plan 写 DeepSeek SDK，按 Change 4 冻结决策统一用 openai SDK（OpenAI 兼容），
总结建议调用 response_format=json_object + json_guard 同栈。

## D5：22:00 时区与批处理
APScheduler cron(hour=22, minute=0, timezone=Asia/Shanghai)；families 列表经 C-23 GET /api/internal/agent/active-families（子代理独立评审发现：afterMigrate 对 families 的 GRANT 排除 deleted_at 列，db_reader 无法过滤软删家庭 → 改走后端）；每家庭独立
claim→run→finish，单家庭失败不阻断其它家庭。锁键语义=UTC 日历日（Change 1 定）。
批次大小 50（plan §Step 2），批间 await sleep(0) 让出事件循环。

## D6：版本保留策略清理
cleanup_expired_versions 每家庭总结成功后执行；DELETE 条件必带 is_important=false；
ROW_NUMBER() OVER (PARTITION BY template_id ORDER BY created_at DESC) rn<=3 兜底。
agent_reader 无 DELETE 权限 → 清理同样走后端新端点？不——清理在总结事务外，
为保持「写 100% 后端」，清理端点 C-22 POST /template-versions/cleanup?family_id=
（返回删除条数）。落地：Java 侧内存排序实现同语义（30 天界 + 每模板最近 3 条豁免），
语义证据 = C22_cleanup_keepsImportantAndYoungest3_deletesOldRest + C22_otherFamilyUntouched；
Python 侧只做 C-22 接线（test_c22_cleanup_called_on_backend_and_parses_deleted）。

## D7：A-2 手动总结路径前缀（偏差记录）
plan/spec 写 `POST /api/agent/summary/trigger`。agent-service A 组对外前缀已定版为
`/api/v1`（A-1 `/api/v1/chat/stream`、A-2 缓冲拆解 `/api/v1/chat`），故手动总结落在
`POST /api/v1/summary/trigger`；`/api/agent/**` 由 Change 6 的 backend 代理层承载
（前端不直连 agent-service，避免 CORS 与 token 透传二次实现）。

## D8：C-20 收尾状态口径
V7 后 status 允许 running/success/failed 三值，但「分类级部分失败」不是新状态：
updated 非空或全绿 → 记 success（失败明细写 failed_categories → error_message）；
updated 为空且有失败 → 记 failed。返回给调用方/B-2 的 JSON status 可为 partial_success，
仅供 Change 6 高亮，不落库。

## D9：三态配对策略（MODIFY 用槽位而非纯相似度）
「家人就地改写 AI 步骤」在数据上表现为同一 sort_order 上的标题变化（C-6 只改 title，
is_ai_generated 仍为 true），因此单靠文本相似度无法判定——plan 例子里
「拖地」→「拖厨房和客厅分开」编辑距离相似度仅 0.125，按 <0.8 会被误判为 REMOVE+ADD。
定版判定顺序（每个样本各计 1 次复现）：
1. 精确：AI 行 title_norm 命中模板步骤 → 同动作，不出差异项；
2. ADD：is_ai_generated=false 的行，norm 不在本样本 AI 集、不在模板基线，
   且与模板任一步骤相似度 <0.5（否则视为改写的近亲，避免重复追加）；
3. MODIFY：未精确命中的 AI 行恰好占据「某缺席基线步骤的原槽位（sort_order）」
   且相似度 <0.8 → 记 (基线步骤, 新文案) 改写对；
4. REMOVE：模板步骤在本样本实际集（AI 行 ∪ 家人新增行）中完全缺席。
槽位而非列表下标：家人删除步骤会留下 sort_order 空洞，天然不会被误当成「整体位移」。
落笔顺序 MODIFY → REMOVE → ADD（替换保位置，删除后再追加），最终按 norm 去重。

## D10：学习需要版本历史可读 → 新增 C-24 + ❌2 幂等
两个刚需都要求读 rule_template_versions 的 change_summary，而该表在 agent_reader 迁移里
只有表级 SELECT、无列级授权（与 families.deleted_at 同类缺口，改走后端最稳）：
- diff 基线：模板 content 会被 C-18 就地覆盖，必须能从版本链回读 AI 原始步骤；
- ❌2 幂等：每日 22:00 + A-2 手动 + force 重跑同一天会重复追加同一条中文警告。
故新增 C-24 GET /templates/{id}/versions?family_id=&limit=（最新在前，limit 1~50），
❌2 追加前读最新版，若 change_summary 已含当日标记「【系统自动警告·YYYY-MM-DD】」则跳过；
跨日标记不同 → 仍会追加新的一条（学习信号不可被旧警告吞掉）。
读历史失败按「未追加」处理：宁可重复一条中文警告，也不能因读不通而漏掉信号。

## D11：跨服务契约缺陷修复——notes target_type 必须小写（计划外发现）
`decomposer` 写便签传 `"TASK"`，但后端 `InternalAgentController:241` 与 V6
`chk_notes_target_type` 只认 `'task'`/`'subtask'`（Java 用例 `C8_C9_addThenDeleteNote_targetTypeGuard`
为权威口径）→ 真机上任何带 notes 的 AI 拆解必 400 → `raise_for_status` → 回滚 `cancel_task`
→ BackendWriteError（56003 语义），即 A-1/A-2/B-1 的 happy path 全部落空。
Change 4 单测全用 FakeBackend（不经 HTTP），故从未暴露。三层修复：
① 编排层传小写；② 连接器 `_note_target()` 归一并拒绝非法值（不发请求，防再次漂移）；
③ 测试把「线上真实报文必须是小写」钉住——wire 层捕获 request.content 断言
`"target_type":"task"`（保留 `"TASK"` 入参以证明归一生效）+ 编排层断言 FakeBackend 记录的 payload。
教训（进 handoff）：跨服务枚举契约必须有至少一条「经过真实 HTTP 报文」的断言，Fake 层断言不构成证据。

## D12：跨服务契约缺陷修复——分类清单改走 C-10（计划外发现）
`DbReader.get_categories` 用 `deleted_at IS NULL` 过滤软删分类，但 afterMigrate 对 categories 的
列授权**排除 deleted_at**（与 families 同，Change 1 权限矩阵有意为之）→ 一旦配置
`DB_AGENT_READER_URL` 指向真机 PG，拆解 Step 2 必触发 42501 permission denied（非
AgentServiceError → A-1/A-2/B-1 直接 500）。Change 4 该类查询标 `# pragma: no cover`，同样从未真机执行。
修法与 D5 同源：删除 `DbReader.get_categories`，分类唯一来源改为后端 C-10（已按 family_id +
软删过滤，且被本 change summarizer 使用）；`get_context` 保留 categories 空键不破坏调用方。
漂移守卫：`test_db_reader_respects_agent_reader_grant_matrix` 断言 ① `DbReader` 不再有
`get_categories`；② 模块级 `_SQL_*` 常量集中真机 SQL 且全部不含 `deleted_at`（新增查询必须走常量，
否则 len==2 断言即红）。
残余风险如实记录：若后端 C-10 与前端分类口径不一致，LLM 可能选到刚被软删的分类；后端 C-3 写入按
family_id 校验归属，Change 7 E2E 覆盖该竞态。

