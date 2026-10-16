"""总结运行：同进程互斥、按日期补偿、失败分类重试和可靠状态报告。"""

from __future__ import annotations

import asyncio
import logging
import weakref
from src.work_auth import current_identity
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta, timezone
from typing import Any
from zoneinfo import ZoneInfo

from src.config import settings
from src.services.mcp_gateway import call_tool
from src.services.summary_collect import CategoryGroup, collect_summary_input
from src.services.summary_learn import learn_all, updated_categories

logger = logging.getLogger("agent_service.summary")

# 运行级失败的退避序列（spec 固化：1 分钟 → 5 分钟 → 30 分钟，最多 3 次重试）
RETRY_DELAYS_SECONDS: tuple[float, ...] = (60.0, 300.0, 1800.0)
# 连续失败多少天后，下次运行合并这些天的数据窗口
WIDEN_AFTER_FAILED_DAYS = 3
# 判定同日防重与扩窗时拉取的执行日志条数
_EXECUTION_LOOKBACK = 200
# 单进程 MVP 的定时和手动入口共用互斥；多 worker 需数据库执行租约。
_run_locks: weakref.WeakValueDictionary[str, asyncio.Lock] = weakref.WeakValueDictionary()


class SummaryAlreadyRun(Exception):
    """当天已成功执行过总结且未带 force（路由层映射 409）。"""


@dataclass
class RunResult:
    """一次运行的结果（路由层直接序列化为响应体）。"""

    status: str  # success | failed
    trigger_source: str  # auto | manual
    categories_updated: list[str] = field(default_factory=list)
    error_message: str | None = None
    attempts: int = 0
    window_since: datetime | None = None
    cancelled_drafts: int = 0


def _local_now() -> datetime:
    """当前时刻（配置时区）。独立成函数便于测试注入桩时钟。"""
    return datetime.now(ZoneInfo(settings.tz))


def _midnight(day: date) -> datetime:
    """某天的 0:00（配置时区）：数据窗口起点。"""
    return datetime.combine(day, time.min, tzinfo=ZoneInfo(settings.tz))


async def fetch_executions(limit: int = _EXECUTION_LOOKBACK) -> list[dict[str, Any]]:
    """执行日志（run_at 倒序）：同日防重与扩窗判定共用一次查询。"""
    rows: list[dict[str, Any]] = []
    offset = 0
    while True:
        page = (
            await call_tool(
                "get_summary_executions", {"limit": limit, "offset": offset}
            )
            or []
        )
        rows.extend(page)
        if len(page) < limit:
            return rows
        offset += len(page)


def succeeded_today(executions: list[dict[str, Any]], run_day: date) -> bool:
    """当天是否已有 success 记录（run_at 按配置时区换算日期）。"""
    for row in executions:
        if row.get("status") != "success":
            continue
        moment = _parse_run_at(row.get("run_at"))
        if (
            moment is not None
            and moment.astimezone(ZoneInfo(settings.tz)).date() == run_day
        ):
            return True
    return False


def window_start_day(executions: list[dict[str, Any]], run_day: date) -> date:
    """按本地日期判断连续失败，覆盖完整失败段而非固定三天。"""
    days: dict[date, set[str]] = {}
    for row in executions:
        moment = _parse_run_at(row.get("run_at"))
        if moment is not None:
            day = moment.astimezone(ZoneInfo(settings.tz)).date()
            days.setdefault(day, set()).add(str(row.get("status")))
    cursor = run_day - timedelta(days=1)
    count = 0
    while days.get(cursor) == {"failed"}:
        count += 1
        cursor -= timedelta(days=1)
    return cursor + timedelta(days=1) if count >= WIDEN_AFTER_FAILED_DAYS else run_day


def _parse_run_at(value: Any) -> datetime | None:
    """ISO 字符串 → datetime；无法解析返回 None。"""
    if isinstance(value, datetime):
        return value
    if not value:
        return None
    try:
        moment = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return moment


def _group_key(group: CategoryGroup) -> tuple[str | None, str]:
    """冷启动模板创建前后保持同一个键，重试不重复学习已成功的组。"""
    template = group.template or {}
    return group.category_id, str(
        template.get("learning_key")
        or template.get("id")
        or f"cold_start:{group.category_id}"
    )


async def run_summary(
    trigger_source: str,
    *,
    force: bool = False,
    now: datetime | None = None,
    sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
) -> RunResult:
    """运行中即拒绝重复请求；force 只绕过已完成检查，不绕过互斥。"""
    identity = current_identity.get()
    if identity is None:
        raise PermissionError("工作总结缺少已认证用户")
    lock = _run_locks.setdefault(identity.user_id, asyncio.Lock())
    if lock.locked():
        raise SummaryAlreadyRun("总结正在运行，请稍后查看结果")
    async with lock:
        return await _run_summary(trigger_source, force=force, now=now, sleep=sleep)


async def _run_summary(
    trigger_source: str,
    *,
    force: bool = False,
    now: datetime | None = None,
    sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
) -> RunResult:
    """执行一次每日总结（定时与手动共用同一数据范围与处理步骤）。

    `trigger_source` ∈ {auto, manual}；manual 且当天已成功、未带 force 时抛
    `SummaryAlreadyRun`。`now`/`sleep` 供测试注入桩时钟与零延迟退避。
    """
    if trigger_source not in ("auto", "manual"):
        raise ValueError(
            f"trigger_source 必须是 auto 或 manual，收到 {trigger_source!r}"
        )
    moment = now or _local_now()
    run_day = moment.astimezone(ZoneInfo(settings.tz)).date()

    result = RunResult(status="failed", trigger_source=trigger_source)
    last_error: str | None = None
    completed_groups: set[tuple[str | None, str | None]] = set()
    changed_categories: list[str] = []

    for attempt in range(len(RETRY_DELAYS_SECONDS) + 1):
        if attempt:
            await sleep(RETRY_DELAYS_SECONDS[attempt - 1])
        result.attempts = attempt + 1
        try:
            # 读执行日志同样属运行级失败：读不到就无法判定同日防重与扩窗，
            # 必须参与退避重试，而不是静默降级成「当天窗口 + 放行重跑」
            executions = await fetch_executions()
            if (
                trigger_source == "manual"
                and not force
                and succeeded_today(executions, run_day)
            ):
                raise SummaryAlreadyRun(
                    f"{run_day.isoformat()} 的总结已成功执行过；如确需重新运行，"
                    "请携带 force=true 确认（将重新学习并可能再次写回模板）"
                )
            since = _midnight(window_start_day(executions, run_day))
            result.window_since = since
            cancelled = await call_tool(
                "cancel_expired_drafts", {"now": moment.isoformat()}
            )
            result.cancelled_drafts = int((cancelled or {}).get("cancelled_count") or 0)
            groups = await collect_summary_input(since, moment)
            pending = [g for g in groups if _group_key(g) not in completed_groups]
            outcomes = await learn_all(pending, run_day)
            # learn_all 保持输入分组顺序；失败的分组重试，成功分组本轮不重复学习。
            for group, outcome in zip(pending, outcomes):
                if not outcome.error:
                    completed_groups.add(_group_key(group))
            changed_categories.extend(updated_categories(outcomes))
            result.categories_updated = list(dict.fromkeys(changed_categories))
            errors = [o.error for o in outcomes if o.error]
            if errors:
                raise RuntimeError("；".join(errors))
            result.status = "success"
            result.error_message = None
            last_error = None
            break
        except SummaryAlreadyRun:
            # 同日防重是业务拒绝（路由层映射 409）：不重试、不写执行日志
            raise
        except Exception as exc:  # 运行级失败：读日志/清理/采集异常，进入退避重试
            last_error = str(exc) or type(exc).__name__
            logger.warning(
                "总结运行失败，准备重试 trigger_source=%s attempt=%d error=%s",
                trigger_source,
                attempt + 1,
                last_error,
                extra={"trigger_source": trigger_source, "attempt": attempt + 1},
            )

    if last_error is not None:
        # 重试耗尽：当天跳过，记 failed 与原因，不发任何通知（spec 固化）
        result.status = "failed"
        result.error_message = last_error
        logger.error(
            "总结运行重试耗尽，当天跳过 trigger_source=%s error=%s",
            trigger_source,
            last_error,
            extra={"trigger_source": trigger_source, "error": last_error},
        )

    try:
        await _log_execution(result, moment)
    except Exception as exc:
        result.status = "failed"
        result.error_message = (
            f"{result.error_message or ''} 执行日志未保存：{exc}".strip()
        )
    return result


async def _log_execution(result: RunResult, moment: datetime) -> None:
    """写入执行日志；失败向运行层报告，不能返回假成功。"""
    try:
        await call_tool(
            "log_summary_execution",
            {
                "status": result.status,
                "categories_updated": result.categories_updated,
                "error_message": result.error_message,
                "trigger_source": result.trigger_source,
                "run_at": moment.isoformat(),
            },
        )
    except Exception as exc:
        logger.error(
            "写总结执行日志失败 status=%s error=%s",
            result.status,
            exc,
            extra={"status": result.status, "error": str(exc)},
        )
        raise
