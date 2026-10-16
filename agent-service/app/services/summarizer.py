"""每日总结编排（Change 5 Task 1~3）：❌2 学习信号 → 分类循环 → 自动学习新版本 → 收尾。

链路（每家庭一次持锁执行）：
  1. C-19 抢当日锁（uk_sel_family_run_date 互斥，未抢到直接 skip）；
  2. ❌2 cancelled-draft 信号：近 7 天该家庭 CANCELLED+AGENT 任务按分类 ≥5 次
     → C-21 向该分类分解模板最新版追加中文警告（当日幂等，D10）；
  3. 分类循环（单分类 asyncio.timeout=60s，Q79 容错）：
     db_reader 取近 30 天同分类 COMPLETED 实际数据（子待办 is_ai_generated + 家人便签）
     → diff_engine 三态学习（D9）→ 触发则调 LLM 出中文 tips（json_guard 严格解析）
     → C-18 提交新版本（写操作 100% 经 backend_client）；
  4. C-22 版本保留策略清理（is_important 永久 / 普通 30 天 / 每模板最少 3）；
  5. C-20 收尾 running→success/failed（D8 口径；未预期异常也必收尾，锁不悬挂）。

家庭隔离：所有读写签名强制 family_id:int（L2 守卫在 connectors 内），
LLM 提示词不含跨家庭数据；写操作全部经后端按 family_id 校验归属。
"""
import asyncio
from datetime import date
from pathlib import Path
from typing import Any

from jinja2 import Environment, FileSystemLoader, StrictUndefined
from loguru import logger

from ..connectors.backend_client import BackendClient
from ..connectors.db_reader import DbReader
from ..harness.json_guard import parse_summary
from ..harness.llm_client import LLMClient
from ..models.diff_types import DiffReport, SubtaskView, TaskSample
from ..models.llm_schemas import LLMSummarySuggestion
from ..settings import get_settings
from .diff_engine import DiffEngine

_CANCEL_WINDOW_DAYS = 7
_CANCEL_THRESHOLD = 5
_CANCEL_WARNING_MARKER = "此分类近 7 天家人连续取消 AI 提案"
_SAMPLE_WINDOW_DAYS = 30
_SAMPLE_LIMIT = 10
_MIN_SAMPLES = 2
_VALID_TRIGGERS = ("auto", "manual")


class Summarizer:
    """单家庭每日总结编排器（依赖注入，测试全假件）。"""

    def __init__(
        self,
        backend: BackendClient | None = None,
        db: DbReader | None = None,
        llm: LLMClient | None = None,
        engine: DiffEngine | None = None,
        per_category_timeout: float | None = None,
    ) -> None:
        self._backend = backend or BackendClient()
        self._db = db or DbReader()
        self._llm = llm
        self._engine = engine or DiffEngine()
        self._timeout = (
            per_category_timeout
            if per_category_timeout is not None
            else float(get_settings().summary_timeout_per_category)
        )
        env = Environment(
            loader=FileSystemLoader(Path(__file__).parent.parent / "prompts"),
            undefined=StrictUndefined,
            keep_trailing_newline=True,
        )
        self._tpl_summary = env.get_template("summary_per_category.j2")

    @property
    def backend(self) -> BackendClient:
        return self._backend

    @property
    def db(self) -> DbReader:
        return self._db

    @property
    def per_category_timeout(self) -> float:
        return self._timeout

    # ==================== 1. 分布式锁 ====================

    async def claim(self, family_id: int, trigger_source: str = "auto", force: bool = False) -> dict[str, Any]:
        """C-19 抢当日锁（唯一冲突 → acquired=false，绝不「先 SELECT 再 INSERT」留竞争窗口）。"""
        if trigger_source not in _VALID_TRIGGERS:
            raise ValueError(f"trigger_source 只允许 {'/'.join(_VALID_TRIGGERS)}，收到 {trigger_source!r}")
        return await self._backend.claim_summary_lock(family_id, trigger_source, force)

    # ==================== 2. ❌2 cancelled-draft 学习信号 ====================

    async def analyze_cancelled_draft_signal(self, family_id: int) -> dict[int, int]:
        """❌2 补项（总结第一小步）：近 7 天 ≥5 次取消 agent 提案的分类 → 追加中文警告。"""
        signal: dict[int, int] = await self._db.get_cancelled_agent_signal(
            family_id, window_days=_CANCEL_WINDOW_DAYS, threshold=_CANCEL_THRESHOLD
        )
        if not signal:
            return {}
        templates = await self._backend.get_decomposition_templates(family_id)
        by_category: dict[int, int] = {}
        for tpl in templates:
            cat_id = tpl.get("category_id", tpl.get("categoryId"))
            if isinstance(cat_id, int):
                by_category[cat_id] = int(tpl["id"])
        today = date.today().isoformat()
        marker = f"【系统自动警告·{today}】"
        for cat_id, cnt in signal.items():
            template_id = by_category.get(cat_id)
            if template_id is None:
                logger.info("❌2 family={} category={} 无启用分解模板，跳过警告追加", family_id, cat_id)
                continue
            if await self._warning_already_appended(family_id, template_id, marker):
                logger.info("❌2 family={} template={} 今日已追加过，幂等跳过", family_id, template_id)
                continue
            warning = (
                f"⚠️ {marker}{_CANCEL_WARNING_MARKER} {cnt} 次（≥{_CANCEL_THRESHOLD} 触发阈值）。"
                "请检查：1. 模板粒度太细太繁？2. 家务规则与家人习惯不符？3. 系统提示词是否需调整？"
                "建议：与家人沟通后在 Templates 页点 ⭐ 标记此版本为重要保留旧版本，再调整生成新版本。"
            )
            try:
                await self._backend.append_change_summary(family_id, template_id, warning)
                logger.info("❌2 警告已追加 family={} category={} count={}", family_id, cat_id, cnt)
            except Exception as exc:  # noqa: BLE001 警告追加失败不阻断总结主体
                logger.warning("❌2 警告追加失败 family={} category={} error={}", family_id, cat_id, exc)
        return signal

    async def _warning_already_appended(self, family_id: int, template_id: int, marker: str) -> bool:
        """❌2 当日幂等（D10）：最新版 change_summary 已含当日标记则跳过。

        读失败按「未追加」处理——宁可重复一条中文警告，也不能因读不通而漏掉学习信号。
        """
        try:
            versions = await self._backend.get_template_versions(family_id, template_id, limit=1)
        except Exception as exc:  # noqa: BLE001
            logger.warning("❌2 读版本历史失败 family={} template={} error={}", family_id, template_id, exc)
            return False
        return any(marker in str(v.get("change_summary") or "") for v in versions)

    # ==================== 3. 分类循环（单分类 60s 超时） ====================

    async def summarize_categories(
        self,
        family_id: int,
        only_category_ids: list[int] | None = None,
        cancelled_signal: dict[int, int] | None = None,
    ) -> tuple[list[int], list[dict[str, Any]], int]:
        """返回 (已更新分类 id 列表, 失败分类明细, 新版本条数)。"""
        signal = cancelled_signal or {}
        categories = await self._backend.get_categories(family_id)  # C-10（软删过滤在后端）
        templates = await self._backend.get_decomposition_templates(family_id)  # C-11
        by_category: dict[int, dict[str, Any]] = {}
        for tpl in templates:
            cat_id = tpl.get("category_id")
            if isinstance(cat_id, int):
                by_category.setdefault(cat_id, tpl)  # 同分类多模板时取 id 最小者（C-11 已按 id 升序）

        updated: list[int] = []
        failed: list[dict[str, Any]] = []
        created = 0
        scope = set(only_category_ids) if only_category_ids else None
        for cat in categories:
            cat_id = cat.get("id")
            if not isinstance(cat_id, int):
                continue
            if scope is not None and cat_id not in scope:
                continue
            template = by_category.get(cat_id)
            if template is None:
                continue  # 该分类无启用的分解模板 → 无可学习对象，不算失败
            name = str(cat.get("name") or f"分类{cat_id}")
            try:
                async with asyncio.timeout(self._timeout):
                    report = await self._learn_one_category(
                        family_id, cat_id, name, template, int(signal.get(cat_id) or 0)
                    )
            except TimeoutError:
                reason = f"单分类总结超时（{self._timeout:g} 秒，Q79 容错降级），已跳过不阻断其它分类"
                logger.warning("分类总结超时 family={} category={}", family_id, cat_id)
                failed.append({"category_id": cat_id, "reason": reason})
                continue
            except Exception as exc:  # noqa: BLE001 单分类失败不阻断其它分类
                logger.error("分类总结失败 family={} category={} error={}", family_id, cat_id, exc)
                failed.append({"category_id": cat_id, "reason": f"未预期错误：{exc}"})
                continue
            if report is None:
                continue
            updated.append(cat_id)
            created += 1
        return updated, failed, created

    async def _learn_one_category(
        self,
        family_id: int,
        category_id: int,
        category_name: str,
        template: dict[str, Any],
        cancelled_count: int = 0,
    ) -> DiffReport | None:
        """单分类学习：取实际数据 → diff → （有改动才）问 LLM → C-18 提交新版本。"""
        rows = await self._db.get_recent_completed(
            family_id, category_id, window_days=_SAMPLE_WINDOW_DAYS, limit=_SAMPLE_LIMIT
        )
        samples = self._to_samples(rows)
        if len(samples) < _MIN_SAMPLES:
            logger.info("样本不足 family={} category={} samples={}，跳过", family_id, category_id, len(samples))
            return None
        raw_content: Any = template.get("content")
        content: dict[str, Any] = raw_content if isinstance(raw_content, dict) else {}
        report = self._engine.build_diff(
            family_id=family_id,
            template_id=int(template["id"]),
            category_id=category_id,
            template_content=content,
            samples=samples,
        )
        if not report.should_emit_new_version:
            return None
        suggestion = await self._ask_llm(
            category_name,
            [str(s) for s in (content.get("common_steps") or [])],
            samples,
            report,
            cancelled_count,
        )
        new_content: dict[str, Any] = {
            **content,
            "common_steps": report.new_common_steps,
            "notes": report.new_template_notes,
            "count": len(report.new_common_steps),
        }
        await self._backend.create_new_template_version(
            family_id,
            int(template["id"]),
            new_content,
            self._change_summary(category_name, report, suggestion),
            suggestion.mark_important,
        )
        logger.info(
            "自动学习落版本 family={} category={} template={} steps={}",
            family_id, category_id, template["id"], len(report.new_common_steps),
        )
        return report

    @staticmethod
    def _to_samples(rows: list[dict[str, Any]]) -> list[TaskSample]:
        """db_reader 原始行 → TaskSample（缺标题的子待办丢弃，避免空 norm 干扰配对）。"""
        samples: list[TaskSample] = []
        for row in rows:
            subtasks = [
                SubtaskView(
                    title=str(s.get("title") or "").strip(),
                    description=(str(s["description"]) if s.get("description") else None),
                    sort_order=int(s.get("sort_order") or 0) or 1,
                    is_ai_generated=bool(s.get("is_ai_generated")),
                )
                for s in (row.get("subtasks") or [])
                if str(s.get("title") or "").strip()
            ]
            samples.append(
                TaskSample(
                    task_id=int(row["task_id"]),
                    subtasks=subtasks,
                    notes=[str(n) for n in (row.get("notes") or []) if str(n).strip()],
                )
            )
        return samples

    async def _ask_llm(
        self,
        category_name: str,
        template_steps: list[str],
        samples: list[TaskSample],
        report: DiffReport,
        cancelled_count: int,
    ) -> LLMSummarySuggestion:
        """中文 tips 问答（成本纪律：只在真要产出新版本时调用）。

        任何失败（超时/格式不合法）都降级为空建议——结构化学习结论来自 diff_engine，
        绝不因 LLM 不可用而丢版本（proposal §非目标：LLM 文本仅作 tips 附加）。
        """
        prompt = self._tpl_summary.render(
            category_name=category_name,
            template_steps=template_steps,
            diff_lines=(report.change_summary_cn or "").splitlines(),
            samples=samples,
            cancelled_count=cancelled_count,
        )
        messages: list[dict[str, Any]] = [
            {"role": "system", "content": "你是家庭事务总结助手，只输出简体中文 JSON 对象。"},
            {"role": "user", "content": prompt},
        ]
        try:
            # LLM 客户端懒构造：抢锁/读样本等前置步骤不需要模型，也避免空 key 构造失败
            llm = self._llm or LLMClient()
            raw = await llm.stream_chat(
                messages, response_format={"type": "json_object"}
            )
            return parse_summary(raw)
        except Exception as exc:  # noqa: BLE001
            logger.warning("总结建议 LLM 降级（不影响模板学习）category={} error={}", category_name, exc)
            return LLMSummarySuggestion()

    @staticmethod
    def _change_summary(category_name: str, report: DiffReport, suggestion: LLMSummarySuggestion) -> str:
        """新版本 change_summary：全中文，逐条写「学到什么动作 + 复现次数 + 阈值」。"""
        lines = [f"【{date.today().isoformat()}】自动总结·家人行为学习（分类：{category_name}）"]
        if report.change_summary_cn:
            lines.append(report.change_summary_cn)
        if suggestion.additional_tips_cn.strip():
            lines.append(f"• AI 辅助建议：{suggestion.additional_tips_cn.strip()}")
        if suggestion.suggested_common_steps_add:
            joined = "、".join(suggestion.suggested_common_steps_add[:5])
            lines.append(f"• AI 建议补充（未自动执行）：{joined}")
        if suggestion.frequency_removals:
            joined = "、".join(suggestion.frequency_removals[:5])
            lines.append(f"• AI 建议减少（未自动执行）：{joined}")
        return "\n".join(lines)

    # ==================== 4. 收尾 ====================

    async def run_family(
        self,
        family_id: int,
        trigger_source: str = "auto",
        force: bool = False,
        only_category_ids: list[int] | None = None,
    ) -> dict[str, Any] | None:
        """抢锁→执行→收尾。未抢到锁返回 None（当日已跑，skip）。"""
        claim = await self.claim(family_id, trigger_source, force)
        if not claim.get("acquired"):
            logger.info("总结跳过（当日已跑） family={} trigger={}", family_id, trigger_source)
            return None
        return await self.run_claimed(family_id, int(claim["log_id"]), only_category_ids)

    async def run_claimed(
        self, family_id: int, log_id: int, only_category_ids: list[int] | None = None
    ) -> dict[str, Any]:
        """已持锁的总结主体（22:00 调度、A-2 手动、B-2 回调共用）。"""
        try:
            signal = await self.analyze_cancelled_draft_signal(family_id)
            updated, failed, created = await self.summarize_categories(
                family_id, only_category_ids=only_category_ids, cancelled_signal=signal
            )
            cleaned = await self._cleanup_versions(family_id)
            log_status = "failed" if (failed and not updated) else "success"
            overall = "partial_success" if (failed and updated) else ("failed" if failed else "success")
            result: dict[str, Any] = {
                "status": overall,
                "categories_updated": updated,
                "failed_categories": failed,
                "versions_created": created,
                "versions_cleaned": cleaned,
                "cancelled_signal": {str(k): v for k, v in signal.items()},
            }
            await self._backend.finish_summary_log(family_id, log_id, log_status, updated, failed)
            logger.info(
                "总结完成 family={} status={} updated={} failed={} versions={}",
                family_id, overall, len(updated), len(failed), created,
            )
            return result
        except Exception as exc:  # noqa: BLE001 未预期错误：收尾 failed 后上抛（锁不悬挂）
            logger.error("总结失败 family={} error={}", family_id, exc)
            try:
                await self._backend.finish_summary_log(
                    family_id, log_id, "failed", [],
                    [{"category_id": 0, "reason": f"未预期错误：{exc}"}],
                )
            except Exception:  # noqa: BLE001
                logger.warning("收尾（failed）写入失败 family={}", family_id)
            raise

    async def _cleanup_versions(self, family_id: int) -> int:
        """C-22 版本保留策略（写操作仍 100% 经后端）；清理失败只告警，不影响本次学习结果。"""
        try:
            deleted = await self._backend.cleanup_template_versions(family_id)
        except Exception as exc:  # noqa: BLE001
            logger.warning("版本保留策略清理失败（不阻断） family={} error={}", family_id, exc)
            return 0
        if deleted:
            logger.info("版本保留策略清理 family={} deleted={}", family_id, deleted)
        return deleted
