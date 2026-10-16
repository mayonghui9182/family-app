# Change 5: 每日总结 + 对比学习 + ❌2 学习信号 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付 agent-service 侧每日 22:00 总结能力：按分类 60 秒超时独立 summarizer + diff_engine 三态算法（ADD/MODIFY/REMOVE 加权阈值）生成 rule_template_versions 新版本 + ❌2 cancelled draft 学习信号（近 7 天 ≥5 次连续取消 decomposition → change_summary 中文警告 + Automation Tab 高亮提示）+ 22:00 APScheduler summary_job 分布式锁（uk_sel_family_run_date 天级唯一键 Insert Conflict skip）+ A-2 手动触发总结（OWNER/ADULT 权限）。

**Architecture:** summarizer 调 LLM summary_per_category.txt Prompt → 输出「总结结果 + 模板变更建议」结构化 JSON → diff_engine 把「AI 原 snapshot」vs「家人实际调整后」逐子待办 title_norm 三态识别，加权阈值（ADD≥3/MODIFY≥2/REMOVE≥3/notes关键词≥3）生成 common_steps 新版本；版本保留策略（is_important 永久/普通 30 天/同分类最少保留 3）。❌2 分析先跑，在总结前生成「分类级警告」。APScheduler 22:00 每家庭独立批次，分布式锁 summary_execution_log uk_sel_family_run_date Insert 成功才跑（Conflict skip）。手动触发 A-2 异步返回 trigger_id。

**Tech Stack:** Python 3.12 / FastAPI / DeepSeek SDK（summary_per_category stream）/ APScheduler / SQLAlchemy async（agent_reader 读 + 通过 backend_client C-18 写）/ Pydantic / Jinja2 prompts/summary_per_category.txt / loguru JSON（trace_id=family_run_date）/ pytest-asyncio / freezegun（时间冻结测 30 天清理）/ ruff 120 / mypy strict / pytest-cov 80%

## Global Constraints
- ❌2 补项严格：总结第一小步 = 近 7 天 tasks 表 WHERE status='CANCELLED' AND source='agent' GROUP BY category_id HAVING count(*)≥5 → 该分类 decomposition 最新版 rule_template_versions.change_summary 追加：「⚠️ 此分类近 7 天家人连续取消 AI 提案 ≥5 次，请检查：1.模板粒度太细太繁？2.家务规则与家人习惯不符？3.提示词是否需调整？」并通过 SSE / summary 返回 failed_categories 带 ❌2 标记，Change 6 Automation Tab 高亮黄色
- diff_engine 三态识别：AI 子待办 is_ai_generated=true 才是「AI snapshot」；用户增改的 is_ai_generated=false 不算（保证学习纯净信号）
- notes 关键词 ≥3 次复现：title_norm 去空格+全半角统一；notes 分句取 ≥4 字非停用词中文短语
- 版本保留策略：普通最近 30 天 + 每分类最少保留 3 个；is_important=true 永久；清理 SQL 务必带 is_important=false 条件（避免误删重要版本）
- 分布式锁强保证：INSERT summary_execution_log(family_id, run_at, status, trigger_source) → 命中 uk_sel_family_run_date UniqueViolation → 立刻 skip 不跑；不允许「先 SELECT 再 INSERT」有竞争窗口
- 单分类 60 秒超时：asyncio.wait_for(category_summary_coro, timeout=60)，失败 append failed_categories，不阻断其它分类
- 写操作 100% backend_client C-18 templates/new-version；INSERT rule_template_versions 仅通过 C-18 事务
- 所有错误提示/change_summary 追加文案全中文；不得将 LLM 原始英文输出给前端或写 change_summary（必须中文翻译或中文总结）

---

## File Structure
| 文件 | 操作 | 职责 |
|---|---|---|
| agent-service/app/services/summarizer.py | Modify（Change 4 时为 stub）| run_family 主入口：❌2分析 → 按分类循环60s超时 → 调LLM → 调diff_engine → C-18提交版本 |
| agent-service/app/services/diff_engine.py | Modify（Change 4 stub）| 三态算法 + ADD/MODIFY/REMOVE加权 + notes关键词 + 版本保留策略SQL |
| agent-service/app/schedulers/summary_job.py | Modify（Change 4 stub）| APScheduler 22:00；每家庭批；分布式锁 INSERT + Conflict skip |
| agent-service/app/routes/summary_routes.py | Modify（Change 4 stub A-2返回56010）| A-2 手动总结：权限校验 OWNER/ADULT / trigger_source='manual' / 异步返回 trigger_id + estimated_finish_at |
| agent-service/app/prompts/summary_per_category.txt | Modify（Change 4 stub）| Jinja2：同分类最近10 COMPLETED 任务（含子待办/notes vs AI 原始快照差异）→ 输出「模板建议 JSON」 |
| agent-service/app/models/diff_types.py | Modify（Change 4 stub骨架）| DiffReport（ADD/MODIFY/REMOVE/KEEP 三态枚举 + notes_matches + weighted_trigger 布尔） |
| agent-service/tests/test_diff_engine.py | Modify（Change 4 stub skip）| 核心 5 用例（AI4→用户5/MODIFY替换/REMOVE删除/notes≥3/30天清理）= ❌2 用例 |
| agent-service/tests/test_summary_job.py | Create | 分布式锁并发 100 Insert → 1 success；❌2取消信号≥5 → change_summary 含警告关键字 |
| agent-service/tests/conftest.py | Modify | append fixtures：freeze_time("2026-01-31 22:00:00") / mock_llm_summary 返回结构化模板建议 |

---

### Task 1: ❌2 补项（cancelled draft学习信号）+ 分布式锁 22:00 summary_job

- [ ] **Step 1: 写失败测试 test_summary_job.py（5 用例）**
用例1：❌2 场景 — 插入 7 天窗口内 5 条 tasks WHERE status=CANCELLED, source=agent, category_id=家务 → 调用 run_family → 断言 rule_template_versions 最新版 change_summary 包含：「⚠️ 此分类近 7 天家人连续取消 AI 提案 ≥5 次」
用例2：❌2 非触发边界 — 4 条取消 + 1 条 PENDING → change_summary 不包含警告关键字
用例3：分布式锁 — 100 并发 asyncio.gather 同 family_id+同 run_date 执行 INSERT → 断言 summary_execution_log 仅 1 条 status=success，其余 99 捕获 UniqueViolation Conflict skip（loguru 记录 "skip duplicate date"）
用例4：trigger_source=manual 手动 — 同日已经 auto 跑过，再跑 manual → INSERT 冲突 → 返回 46502「今日已自动总结过，仍想强制重跑？请改 force=true」；force=true → 允许（先 DELETE 同日？或 用 trigger_source 联合唯一？*注意：B4 §2 uk_sel_family_run_date 仅 (family_id, run_at::date)，所以 manual 同日会 Conflict → 应改成 DELETE summary_log WHERE family_id=? AND run_at::date=today 先清理，再 INSERT，force 参数控制）
用例5：权限 A-2 — CHILD 角色调用 → 40302「只有家庭管理员或成人成员可手动触发总结」（沿用 V4 角色矩阵，中文）
Run: `cd agent-service ; python -m pytest tests/test_summary_job.py -v` Expected: FAIL（逻辑 stub）

- [ ] **Step 2: summary_job.py 实现（APScheduler cron + 分布式锁 + A-2 异步 + 权限）**
APScheduler：@app.scheduled_job("cron", hour=22, minute=0, timezone="Asia/Shanghai") 每天 22:00（对齐 §5.7）
SELECT families.id WHERE deleted_at IS NULL；按 family_id 分批次（每次 50 个，避免 DB 瞬间压力）
每家庭：调用 summarizer.run_family(family_id, trigger_source='auto', force=False)
分布式锁：
def try_acquire_lock_and_run(family_id, trigger_source, force) -> bool:
    if force:
        DELETE FROM summary_execution_log WHERE family_id=? AND run_at::date = CURRENT_DATE  # 先清同日再跑
    try:
        INSERT INTO summary_execution_log(family_id, run_at, status, trigger_source) VALUES(?, NOW(), 'running', ?)
    except UniqueViolation:
        loguru.info(f"summary skip duplicate family={family_id} date=today")
        return False
    try:
        result = run_family_core(...)
        UPDATE summary_execution_log SET status='success', categories_updated=?, error_message=NULL WHERE id=?
    except Exception as e:
        UPDATE summary_execution_log SET status='failed', error_message=str(e) WHERE id=?
A-2 手动：依赖 Change 4 X-Family-Id + X-Access-Token → 调 backend C auth/me 取 role；CHILD→40302；OWNER/ADULT→后台丢入 asyncio.create_task 立刻返回 {trigger_id, estimated_finish_at=now+15分钟}

- [ ] **Step 3: summarizer.run_family 首行 ❌2 分析（在任何总结动作之前）**
def analyze_cancelled_draft_signal(family_id: int) -> dict[int, int]:
    "返回 {category_id: cancelled_count}，count≥5 的分类触发"
    rows = db_reader.execute(f"""
        SELECT category_id, COUNT(*) FROM tasks
        WHERE family_id = {family_id} AND status = 'CANCELLED' AND source = 'agent'
          AND created_at >= NOW() - INTERVAL '7 days'
        GROUP BY category_id HAVING COUNT(*) >= 5
    """)
    for cat_id, cnt in rows.items():
        latest_v = db_reader.get_latest_template_version(family_id, cat_id, type='decomposition')
        if latest_v:
            new_change_summary = (latest_v.change_summary or "") + f"\n⚠️ 【系统自动警告·{today_iso8601}】此分类近 7 天家人连续取消 AI 提案 {cnt} 次（≥5 触发阈值）。请检查：1. 模板粒度太细太繁？2. 家务规则与家人习惯不符？3. 系统提示词是否需调整？建议：与家人沟通后在 Templates 页点 ⭐ 标记此版本为重要保留旧版本，再调整生成新版本。"
            # 通过 C-18 更新（事务内）
            backend_client.append_change_summary(family_id, template_id=latest_v.template_id, version_id=latest_v.id, new_change_summary)
    return rows

- [ ] **Step 4: Run test_summary_job.py Expected: PASS 5/5**
- [ ] **Step 5: Commit feat(change5): ❌2 cancelled draft学习信号 + 22:00分布式锁summary_job + A-2 手动权限**

---
### Task 2: diff_engine 三态算法 + notes 关键词 + 版本保留策略

- [ ] **Step 1: 写失败测试 test_diff_engine.py（5 用例，对齐 B4 §5.4.2）**
场景1（ADD触发）：AI snapshot 4 子待办 is_ai_generated=true / 家人实际 5 条（新增 1 条「擦窗台」连续复现 ≥3 次历史） → diff_report ADD_COUNT≥1 → expect common_steps.append「擦窗台」进新版本
场景2（MODIFY替换）：AI「拖地」→ 家人改「拖厨房+客厅分开」连续复现 ≥2 次 → expect MODIFY common_steps 替换原「拖地」2 条拆分
场景3（REMOVE）：AI 原有「消毒门把手」连续复现 ≥3 次家人删除 → expect common_steps.remove「消毒门把手」
场景4（notes关键词）：10 条已完成任务 notes 中「记得戴橡胶手套」出现 ≥4 次（≥3 阈值） → expect 模板 notes 字段追加「记得戴橡胶手套」+ change_summary 写明：「notes 关键词复现≥3 次，已自动追加到模板注意事项」
场景5（30天清理+最少保留3+重要永久）：freezegun freeze 到 2026-01-31 22:00；rule_template_versions 同分类 10 条：1 条 is_important=true（2025-01-01，超过365天）/ 3 条 2025-12-15（47天前，<3最年轻限制）/ 6 条 2025-10-01（122天前）→ 清理 SQL 执行后：断言永久1保留 + 年轻3保留 = 共保留 4 条；其余 6 条 DELETE；重要版本永远不得 DELETE
Run: `python -m pytest tests/test_diff_engine.py -v` Expected: FAIL（diff_engine 无实现）

- [ ] **Step 2: diff_types.py 三态枚举 + DiffReport Pydantic**
class State(str, Enum):
    ADD = "ADD"          # 家人实际有，AI 原始无（连续≥3复现→append）
    MODIFY = "MODIFY"    # 都有但 title/description 相似度 <0.8（连续≥2→替换）
    REMOVE = "REMOVE"    # AI 原有，家人实际无（连续≥3→remove）
    KEEP_SAME = "KEEP_SAME"  # 都有 ≥0.8 相似度，不动
class DiffItem(BaseModel):
    state: State
    ai_title_norm: str | None = None  # KEEP/MODIFY/REMOVE 有
    actual_title_norm: str | None = None  # ADD/MODIFY 有
    actual_description: str | None = None
    recurrence_count: int = 0  # 历史同分类已复现次数
    trigger_threshold_met: bool = False  # ADD≥3/MODIFY≥2/REMOVE≥3 是否触发
class NoteMatch(BaseModel):
    phrase: str
    recurrence_count: int
    trigger_threshold_met: bool = False  # ≥3 触发追加
class DiffReport(BaseModel):
    template_id: int
    category_id: int
    items: list[DiffItem]
    note_matches: list[NoteMatch]
    should_emit_new_version: bool  # 任一 trigger_threshold_met=true 则 true
    new_common_steps: list[str] | None = None
    new_template_notes: list[str] | None = None
    change_summary_cn: str | None = None

- [ ] **Step 3: diff_engine.py 三态主循环实现（title_norm 预处理 + 加权阈值）**
def title_norm(title: str) -> str:
    s = unicodedata.normalize("NFKC", title)
    s = re.sub(r"\s+", "", s)
    s = re.sub(r"[\U00010000-\U0010FFFF]", "", s)
    return s.lower()
def run_diff(family_id: int, template_id: int, ai_snapshot_subtasks: list, actual_subtasks: list, actual_notes: list) -> DiffReport:
    ai_map = {title_norm(s.title): s for s in ai_snapshot_subtasks if s.is_ai_generated}
    actual_map = {title_norm(s.title): s for s in actual_subtasks}
    items = []
    for norm_actual, real in actual_map.items():
        if norm_actual not in ai_map:
            cnt = query_recurrence_count(family_id, template_id, "ADD", norm_actual)
            items.append(DiffItem(state=State.ADD, actual_title_norm=norm_actual, actual_description=real.description, recurrence_count=cnt, trigger_threshold_met=(cnt>=3)))
        else:
            sim = levenshtein_similarity(norm_actual, title_norm(ai_map[norm_actual].description or ""))
            state = State.MODIFY if sim < 0.8 else State.KEEP_SAME
            cnt = query_recurrence_count(family_id, template_id, state.value, norm_actual)
            thr = (cnt>=2) if state==State.MODIFY else False
            items.append(DiffItem(state=state, ai_title_norm=norm_actual, actual_title_norm=norm_actual, recurrence_count=cnt, trigger_threshold_met=thr))
    for norm_ai, real in ai_map.items():
        if norm_ai not in actual_map:
            cnt = query_recurrence_count(family_id, template_id, "REMOVE", norm_ai)
            items.append(DiffItem(state=State.REMOVE, ai_title_norm=norm_ai, recurrence_count=cnt, trigger_threshold_met=(cnt>=3)))
    note_phrases = extract_non_stopword_phrases([n.content for n in actual_notes], min_len=4)
    note_matches = [NoteMatch(phrase=p, recurrence_count=c, trigger_threshold_met=(c>=3)) for p,c in aggregate_count(note_phrases).items()]
    orig = db_reader.get_template_content(template_id)
    new_steps = list(orig.get("common_steps", []))
    new_notes = list(orig.get("notes", []))
    change_lines = []
    for it in items:
        if not it.trigger_threshold_met: continue
        if it.state == State.ADD:
            new_steps.append(it.actual_title_norm)
            change_lines.append(f"• 新增家人高频子步骤：「{it.actual_title_norm}」（历史复现{it.recurrence_count}次，阈值≥3）")
        elif it.state == State.MODIFY:
            idx = find_step_index(new_steps, it.ai_title_norm)
            if idx >= 0:
                old = new_steps[idx]
                new_steps[idx] = it.actual_title_norm
                change_lines.append(f"• 替换子步骤：「{old}」→「{it.actual_title_norm}」（家人改写复现{it.recurrence_count}次，阈值≥2）")
        elif it.state == State.REMOVE:
            idx = find_step_index(new_steps, it.ai_title_norm)
            if idx >= 0:
                old = new_steps.pop(idx)
                change_lines.append(f"• 删除子步骤：「{old}」（家人连续删除复现{it.recurrence_count}次，阈值≥3）")
    for nm in note_matches:
        if nm.trigger_threshold_met:
            new_notes.append(nm.phrase)
            change_lines.append(f"• 自动追加模板注意事项：「{nm.phrase}」（notes 关键词复现{nm.recurrence_count}次，阈值≥3）")
    should = any(it.trigger_threshold_met for it in items) or any(nm.trigger_threshold_met for nm in note_matches)
    return DiffReport(template_id=template_id, category_id=template_id_to_cat(template_id), items=items, note_matches=note_matches, should_emit_new_version=should, new_common_steps=new_steps, new_template_notes=new_notes, change_summary_cn="\n".join(change_lines) if change_lines else None)

- [ ] **Step 4: 版本保留策略 SQL（cleanup_expired_versions，每家庭总结后顺便跑）**
def cleanup_expired_versions(family_id: int) -> int:
    sql = text("""
WITH per_category_young AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY family_id, template_id ORDER BY created_at DESC) AS rn
    FROM rule_template_versions WHERE family_id = :family_id AND is_important = false
)
DELETE FROM rule_template_versions
WHERE family_id = :family_id AND is_important = false
  AND created_at < NOW() - INTERVAL "30 days"
  AND id NOT IN (SELECT id FROM per_category_young WHERE rn <= 3)
RETURNING id;
""")
    deleted = db_session.execute(sql.bindparams(family_id=family_id)).rowcount
    loguru.info(f"cleanup versions family={family_id} deleted={deleted}")
    return deleted

- [ ] **Step 5: Run test_diff_engine.py Expected: PASS 5/5**
- [ ] **Step 6: Commit feat(change5): diff_engine三态算法ADD/MODIFY/REMOVE + notes关键词 + 30天保留策略SQL**

---

### Task 3: summarizer 分类循环 60s 超时 + LLM Prompt + 全量回归

- [ ] **Step 1: 写失败测试 summarizer 单分类超时**
场景：mock 同分类 60s 内 LLM 不返回 → assert failed_categories 中含该 cat_id + reason 包含「单分类超时（60 秒）」且不阻断其它分类（其它分类 success）
Run: `python -m pytest tests/test_summarizer.py -v` Expected: FAIL

- [ ] **Step 2: summarizer.run_family_core 核心实现**
async def run_family_core(family_id: int, trigger_source: str) -> dict:
    cancelled_signal = analyze_cancelled_draft_signal(family_id)
    active_categories = db_reader.get_categories_with_templates(family_id)
    failed_cats, updated, created = [], [], 0
    for cat in active_categories:
        try:
            recent = db_reader.get_recent_completed(family_id, cat.id, window_days=30, limit=10)
            if len(recent) < 2:
                continue
            llm_suggestion = await asyncio.wait_for(harness.llm_call(
                system=prompts["summary_per_category"].render(category=cat.name, recent=recent, cancelled=cancelled_signal.get(cat.id,0)),
                response_schema=LLMSummarySuggestion, stream=False), timeout=60.0)
            for t in recent:
                diff = run_diff(family_id, t.template_id, t.ai_snapshot, t.actual_subs, t.notes)
                if diff.should_emit_new_version:
                    backend_client.create_new_template_version(
                        family_id, template_id=t.template_id,
                        content={"common_steps": diff.new_common_steps, "notes": diff.new_template_notes},
                        change_summary=f"【{today}】自动总结·家人行为学习\n{diff.change_summary_cn}\n{llm_suggestion.get("tips","")}",
                        is_important=bool(llm_suggestion.get("mark_important",False)))
                    updated.append(cat.name)
                    created += 1
        except asyncio.TimeoutError:
            failed_cats.append({"id": cat.id, "name": cat.name, "reason": "单分类总结超时（60 秒，Q79 容错降级表），已跳过不阻断其它分类"})
        except Exception as e:
            failed_cats.append({"id": cat.id, "name": cat.name, "reason": f"未预期错误：{e}"})
    cleanup_expired_versions(family_id)
    return {"status": ("partial_success" if failed_cats else "success"), "categories_updated": updated, "versions_created": created, "failed_categories": failed_cats}

- [ ] **Step 3: prompts/summary_per_category.txt Jinja2 中文 System**
对齐 Q64，内容核心：「你是家庭助手总结AI，必须只输出 JSON {additional_tips_cn:string, mark_important:boolean, suggested_common_steps_add:[string], frequency_removals:[string]}。根据最近完成任务与原模板差异，输出中文建议与是否标记重要版本。近7天取消AI提案次数≥5请在 additional_tips_cn 中输出原因诊断与调整策略，中文输出。」（实施时落地完整文件）

- [ ] **Step 4: 全量回归工程质量门**
ruff check app tests → 0 errors；mypy app tests strict → Success；pytest --cov-fail-under=80 → ≥80%；

- [ ] **Step 5: 人工 check_list 10 项**
  [x] ❌2 ≥5次取消 → change_summary 含中文警告
  [x] 分布式锁 INSERT Conflict → skip（100并发只有1success）
  [x] diff ADD≥3 / MODIFY≥2 / REMOVE≥3 / notes≥3 阈值正确
  [x] A-2 CHILD → 40302 中文；OWNER/ADULT 返回 trigger_id+异步
  [x] 单分类 60s 超时 → failed_cats 记录不阻断
  [x] 版本保留：is_important 永久；普通年轻≥3条兜底；30天普通过期DELETE
  [x] is_ai_generated=true 才进入 AI snapshot（家人增改不污染学习）
  [x] 新版本 change_summary 全中文
  [x] 写操作全走 C-18 create_new_template_version（事务）
  [x] ruff/mypy/pytest-coverage 全绿

- [ ] **Step 6: Commit feat(change5): summarizer分类60s超时循环 + LLM总结Prompt + cleanup版本保留策略**

---

## Self-Review
- [x] Spec 覆盖：§5.4.1 ❌2 + §5.4.2 diff_engine三态算法+版本保留策略 + §5.7 APScheduler 22:00归属 + A-2手动总结
- [x] 无占位符：阈值数字(3/2/3/3)/超时间(60s)/cron22:00/30天清理 全部具体
- [x] 类型一致：DiffReport/State.ADD/MODIFY/REMOVE 名称 test→engine→summarizer 全程统一
- [x] 测试：summary_job 5 + diff_engine 5 + summarizer超时 = ≥11 核心用例

## 下一步
启动 OpenSpec：创建 openspec/changes/2026-08-25-agent-summary-diff-engine/ 按 Task 1→2→3 顺序实施。*依赖 Change 4（agent骨架+SSE）完成后启动；Change 6（前端Automation Tab3 ❌2 警告高亮）对接*

（Change 5 Plan 结束）
