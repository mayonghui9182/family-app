"""分类内一次模型调用，按模板归属验证证据并带版本写回。"""

from __future__ import annotations

import asyncio
import json
import logging
from dataclasses import dataclass, field
from datetime import date, datetime, timezone
from typing import Any
from zoneinfo import ZoneInfo

from src.config import settings
from src.llm.client import LLMClient
from src.llm.prompts import render_prompt
from src.services.mcp_gateway import call_tool
from src.services.summary_collect import CategoryGroup, normalize_step

logger = logging.getLogger("agent_service.summary")

# 手动编辑版本行的变更摘要（task-service 侧同文案，逐字一致）
MANUAL_EDIT_SUMMARY = "用户手动编辑"
# 当天有手动编辑时自动补充的固定摘要（spec 固化文案）
SUPPRESSED_SUMMARY = "自动补充（用户已手动编辑，未覆盖）"
# 无模板分类自动创建的固定摘要（spec 固化文案）
AUTO_CREATE_SUMMARY = "每日总结自动创建"
# 常规自动学习摘要前缀，后接各动作与 reason
AUTO_LEARN_PREFIX = "每日总结自动学习："
# 新建模板的名称模板（按分类命名，便于前端展示）
NEW_TEMPLATE_NAME = "{category_name}拆解模板"

# 合法 action → 必填字段（缺任一字段即跳过该条目）
_REQUIRED_FIELDS: dict[str, tuple[str, ...]] = {
    "add_step": ("step", "reason"),
    "remove_step": ("step", "reason"),
    "update_estimated_count": ("value", "old_value", "reason"),
    "update_notes": ("value", "reason"),
}


@dataclass
class CategoryOutcome:
    """单个分类的学习与写回结果。"""

    category_id: str | None
    category_name: str
    changed: bool = False
    created: bool = False
    # 已应用动作的中文摘要片段（进 change_summary，也用于日志排查）
    applied: list[str] = field(default_factory=list)
    # 被跳过的非法条目原因
    skipped: list[str] = field(default_factory=list)
    # 当天有手动编辑 → 只追加不覆盖
    manual_edit: bool = False
    # 本分类处理失败的中文原因（None 表示成功）
    error: str | None = None


def _build_llm() -> LLMClient:
    """LLM 客户端工厂：独立成函数便于测试注入桩 transport。"""
    return LLMClient()


def _user_message(group: CategoryGroup) -> str:
    """user 消息：数据都在 system prompt 里，这里只给一句执行指令。"""
    return (
        f"分类「{group.category_name}」本窗口内有 {len(group.tasks)} 条任务出现用户修改，"
        "请按学习规则输出 template_updates 与 new_notes。"
    )


async def learn_all(
    groups: list[CategoryGroup],
    run_day: date,
    llm: LLMClient | None = None,
) -> list[CategoryOutcome]:
    """串行处理全部分类，单分类失败隔离（不影响其余分类，也不抛出）。"""
    if not groups:
        # 窗口内没有修改证据：不构造 LLM 客户端，避免把「无数据」误判为运行失败
        return []
    client = llm or _build_llm()
    outcomes: list[CategoryOutcome] = []
    ordered_groups: list[CategoryGroup] = []
    categories: dict[str | None, list[CategoryGroup]] = {}
    for group in groups:
        categories.setdefault(group.category_id, []).append(group)
    for category_groups in categories.values():
        ordered_groups.extend(category_groups)
        try:
            if len(category_groups) == 1:
                outcomes.append(
                    await learn_category(category_groups[0], run_day, llm=client)
                )
                continue
            # 同分类多个模板共享一次调用，输出明确指向模板，证据不跨模板混用。
            context = [
                {
                    "target_key": str(g.template["id"]) if g.template else "cold_start",
                    **g.to_prompt(),
                }
                for g in category_groups
            ]
            prompt = (
                "你是任务模板学习助手。分别处理下列模板分组，不得跨分组引用证据。"
                "同一调整必须在至少2个不同任务出现。每个更新必须含reason和evidence_task_ids。"
                "action只能是add_step(step)、remove_step(step)、"
                "update_estimated_count(value,old_value)、update_notes(value)。"
                '返回{"template_results":[{"target_key":"...","template_updates":[]}]}。数据：'
                + json.dumps(context, ensure_ascii=False)
            )
            async with asyncio.timeout(settings.summary_llm_timeout_seconds):
                result = await client.chat_json(
                    system=prompt,
                    user="输出各模板的更新建议",
                    purpose="summary",
                    timeout_seconds=settings.summary_llm_timeout_seconds,
                )
            rows = result.get("template_results")
            if not isinstance(rows, list):
                raise TypeError("多模板总结缺少 template_results")
            by_key = {}
            for row in rows:
                if not isinstance(row, dict) or row.get("target_key") in by_key:
                    raise ValueError("模板结果格式错误或目标重复")
                by_key[row.get("target_key")] = row
            for group in category_groups:
                key = str(group.template["id"]) if group.template else "cold_start"
                if key not in by_key:
                    outcomes.append(
                        CategoryOutcome(
                            group.category_id,
                            group.category_name,
                            error="模型遗漏模板结果",
                        )
                    )
                    continue
                try:
                    outcomes.append(await _persist_result(group, run_day, by_key[key]))
                except Exception as exc:
                    outcomes.append(
                        CategoryOutcome(
                            group.category_id,
                            group.category_name,
                            error=str(exc) or type(exc).__name__,
                        )
                    )
        except Exception as exc:
            reason = str(exc) or type(exc).__name__
            logger.warning("分类学习失败：%s", reason)
            outcomes.extend(
                CategoryOutcome(g.category_id, g.category_name, error=reason)
                for g in category_groups
            )
    by_group = {id(group): outcome for group, outcome in zip(ordered_groups, outcomes)}
    return [by_group[id(group)] for group in groups]


async def learn_category(
    group: CategoryGroup,
    run_day: date,
    llm: LLMClient | None = None,
) -> CategoryOutcome:
    """学习并写回单个分类：一次 LLM 调用（60 秒超时）→ 校验 → 立即写回。"""
    client = llm or _build_llm()
    timeout = settings.summary_llm_timeout_seconds
    async with asyncio.timeout(timeout):
        result = await client.chat_json(
            system=render_prompt("summary", **group.to_prompt()),
            user=_user_message(group),
            purpose="summary",
            timeout_seconds=timeout,
        )

    return await _persist_result(group, run_day, result)


async def _persist_result(
    group: CategoryGroup, run_day: date, result: dict[str, Any]
) -> CategoryOutcome:
    """格式校验后验证实际样本，拒绝模型虚构的重复模式。"""
    updates, skipped = _validate_updates(result.get("template_updates"))
    verified = []
    tasks = {t.task_id: t for t in group.tasks if t.change_size}
    for item in updates:
        action = item["action"]
        if action in ("add_step", "remove_step"):
            attribute = "added_steps" if action == "add_step" else "deleted_steps"
            evidence = {
                tid
                for tid, t in tasks.items()
                if normalize_step(item["step"])
                in {normalize_step(s) for s in getattr(t, attribute)}
            }
        else:
            ids = item.get("evidence_task_ids")
            evidence = (
                set(ids) & tasks.keys()
                if isinstance(ids, list) and all(isinstance(i, str) for i in ids)
                else set()
            )
            if action == "update_estimated_count":
                evidence = {
                    tid
                    for tid in evidence
                    if len(tasks[tid].current_steps) == item["value"]
                }
                if group.template and item.get("old_value") != group.template.get(
                    "estimated_subtask_count"
                ):
                    evidence = set()
        if len(evidence) < 2:
            skipped.append(f"{action} 缺少两个不同任务的有效证据")
            continue
        verified.append(item)
    outcome = CategoryOutcome(group.category_id, group.category_name, skipped=skipped)
    # new_notes 是观察结果，不是带证据的修改动作，不自动写入模板。
    if group.template is None:
        return await _create_template(group, verified, "", outcome)
    return await _update_template(group, verified, "", run_day, outcome)


# --------------------------------------------------------------------------
# 9.2 输出校验
# --------------------------------------------------------------------------


def _validate_updates(raw: Any) -> tuple[list[dict[str, Any]], list[str]]:
    """过滤 LLM 输出：只保留 action 合法且必填字段齐备的条目。

    非法条目记入 skipped（带中文原因）供日志排查，绝不中断整次运行。
    """
    if not isinstance(raw, list):
        # 模型偶尔会把 template_updates 写成对象或省略：视为「无更新」，不中断
        return [], ([] if raw is None else ["template_updates 不是数组，已忽略"])
    updates: list[dict[str, Any]] = []
    skipped: list[str] = []
    for item in raw:
        if not isinstance(item, dict):
            skipped.append("条目不是对象")
            continue
        action = str(item.get("action") or "").strip()
        required = _REQUIRED_FIELDS.get(action)
        if required is None:
            skipped.append(f"未知 action「{action or '空'}」")
            continue
        missing = [name for name in required if _blank(item.get(name))]
        if missing:
            skipped.append(f"{action} 缺少字段 {'/'.join(missing)}")
            continue
        if action == "update_estimated_count":
            try:
                value = int(item["value"])
            except (TypeError, ValueError):
                skipped.append("update_estimated_count 的 value 不是整数")
                continue
            if value < 0:
                skipped.append("update_estimated_count 的 value 为负数")
                continue
            updates.append({**item, "action": action, "value": value})
            continue
        updates.append(
            {
                **item,
                "action": action,
                **{
                    name: str(item[name]).strip()
                    for name in required
                    if name != "old_value"
                },
            }
        )
    return updates, skipped


def _blank(value: Any) -> bool:
    """必填字段是否缺失：None、空白串、空容器都算缺失。"""
    if value is None:
        return True
    if isinstance(value, str):
        return not value.strip()
    if isinstance(value, (list, dict)):
        return not value
    return False


# --------------------------------------------------------------------------
# 9.3 写回
# --------------------------------------------------------------------------


async def _update_template(
    group: CategoryGroup,
    updates: list[dict[str, Any]],
    new_notes: str,
    run_day: date,
    outcome: CategoryOutcome,
) -> CategoryOutcome:
    """写回既有模板：当天有手动编辑则退化为只追加步骤。"""
    template = group.template or {}
    manual = _same_day(
        template.get("last_manual_edit_at"), run_day
    ) or await _manual_edit_today(template.get("id"), run_day)
    outcome.manual_edit = manual
    if manual:
        # 手动编辑优先：remove_step / update_estimated_count / update_notes 一律抑制
        updates = [item for item in updates if item["action"] == "add_step"]

    content, fragments = _apply_updates(template, updates, new_notes, manual)
    outcome.applied = fragments
    if not _content_changed(template, content):
        # 无实际变化：不写库、不产生版本行、不计入 categories_updated
        return outcome

    change_summary = (
        SUPPRESSED_SUMMARY if manual else AUTO_LEARN_PREFIX + "；".join(fragments)
    )
    await call_tool(
        "update_rule_template",
        {
            "template_id": str(template.get("id")),
            "content": content,
            "expected_version": template["version"],
            "change_summary": change_summary,
        },
    )
    outcome.changed = True
    logger.info(
        "模板写回完成 category=%s template_id=%s manual_edit=%s actions=%d",
        group.category_name,
        template.get("id"),
        manual,
        len(fragments),
        extra={
            "category": group.category_name,
            "template_id": str(template.get("id")),
            "manual_edit": manual,
        },
    )
    return outcome


async def _create_template(
    group: CategoryGroup,
    updates: list[dict[str, Any]],
    new_notes: str,
    outcome: CategoryOutcome,
) -> CategoryOutcome:
    """无模板分类：有 add_step 证据才新建（无分类的兜底组不沉淀模板）。"""
    steps = _dedupe(item["step"] for item in updates if item["action"] == "add_step")
    if not steps:
        return outcome
    if not group.category_id:
        # 「未分类」是归类兜底，不为它创建全局模板
        outcome.skipped.append("分类缺失，未创建模板")
        return outcome

    counts = [
        item["value"] for item in updates if item["action"] == "update_estimated_count"
    ]
    notes = (
        next(
            (item["value"] for item in updates if item["action"] == "update_notes"), ""
        )
        or new_notes
    )
    fragments = [f"新建模板，常见步骤 {'、'.join(steps)}"]
    outcome.applied = fragments
    await call_tool(
        "create_rule_template",
        {
            "template_type": "decomposition",
            "name": NEW_TEMPLATE_NAME.format(category_name=group.category_name),
            "category_id": group.category_id,
            "common_steps": steps,
            "estimated_subtask_count": counts[0] if counts else len(steps),
            "notes": notes,
            "change_summary": AUTO_CREATE_SUMMARY,
            "learning_key": f"cold_start:{group.category_id}",
        },
    )
    outcome.created = True
    outcome.changed = True
    logger.info(
        "模板自动创建 category=%s steps=%d",
        group.category_name,
        len(steps),
        extra={"category": group.category_name, "steps": len(steps)},
    )
    return outcome


def _apply_updates(
    template: dict[str, Any],
    updates: list[dict[str, Any]],
    new_notes: str,
    manual: bool,
) -> tuple[dict[str, Any], list[str]]:
    """按动作顺序算出新 content 与摘要片段。

    幂等保护：已存在的步骤不重复追加、模板里没有的步骤删除视为无操作、
    与现值相同的 count/notes 不产生变化，避免刷出无意义的版本行。
    """
    steps = [str(step) for step in (template.get("common_steps") or [])]
    count = int(template.get("estimated_subtask_count") or 0)
    notes = str(template.get("notes") or "")
    fragments: list[str] = []
    notes_replaced = False

    for item in updates:
        action = item["action"]
        reason = str(item.get("reason") or "").strip()
        if action == "add_step":
            step = item["step"]
            if any(
                normalize_step(existing) == normalize_step(step) for existing in steps
            ):
                continue
            steps.append(step)  # spec：追加到末尾，不重排
            fragments.append(f"新增步骤「{step}」（{reason}）")
        elif action == "remove_step":
            step = item["step"]
            remain = [s for s in steps if normalize_step(s) != normalize_step(step)]
            if len(remain) == len(steps):
                continue
            steps = remain
            fragments.append(f"删除步骤「{step}」（{reason}）")
        elif action == "update_estimated_count":
            value = int(item["value"])
            if value == count:
                continue
            fragments.append(f"预期子待办数 {count}→{value}（{reason}）")
            count = value
        elif action == "update_notes":
            note_value = str(item["value"]).strip()
            if note_value == notes:
                continue
            fragments.append(f"更新注意事项（{reason}）")
            notes = note_value
            notes_replaced = True

    if new_notes and not manual and not notes_replaced and new_notes not in notes:
        notes = f"{notes} {new_notes}".strip()
        fragments.append(f"补充注意事项「{new_notes}」")

    content = {
        "common_steps": steps,
        "estimated_subtask_count": count,
        "notes": notes,
    }
    return content, fragments


def _content_changed(template: dict[str, Any], content: dict[str, Any]) -> bool:
    """写回前比较：只有内容确实变化才算模板变更（决定 categories_updated）。"""
    old = {
        "common_steps": [str(step) for step in (template.get("common_steps") or [])],
        "estimated_subtask_count": int(template.get("estimated_subtask_count") or 0),
        "notes": str(template.get("notes") or ""),
    }
    new = {
        "common_steps": content.get("common_steps") or [],
        "estimated_subtask_count": int(content.get("estimated_subtask_count") or 0),
        "notes": str(content.get("notes") or ""),
    }
    return old != new


def _dedupe(steps: Any) -> list[str]:
    """保序去重并丢弃空白步骤。"""
    return list(dict.fromkeys(str(step).strip() for step in steps if str(step).strip()))


# --------------------------------------------------------------------------
# 9.4 手动编辑优先
# --------------------------------------------------------------------------


async def _manual_edit_today(template_id: Any, run_day: date) -> bool:
    """模板当天是否被用户手动编辑过（版本行 change_summary + created_at 判定）。

    取版本历史失败时**保守返回 True**：宁可少学一次，也不覆盖用户的手动编辑。
    """
    if not template_id:
        return False
    try:
        rows = await call_tool(
            "get_template_versions", {"template_id": str(template_id), "limit": 20}
        )
    except Exception as exc:
        logger.warning(
            "取模板版本历史失败，按「当天已手动编辑」保守处理 template_id=%s error=%s",
            template_id,
            exc,
            extra={"template_id": str(template_id), "error": str(exc)},
        )
        return True
    for row in rows or []:
        if (row.get("change_summary") or "") != MANUAL_EDIT_SUMMARY:
            continue
        if _same_day(row.get("created_at"), run_day):
            return True
    return False


def _same_day(created_at: Any, run_day: date) -> bool:
    """版本行时间是否落在运行日（按配置时区换算；naive 视为 UTC）。"""
    moment = _parse(created_at)
    if moment is None:
        return False
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return moment.astimezone(ZoneInfo(settings.tz)).date() == run_day


def _parse(value: Any) -> datetime | None:
    """ISO 字符串 → datetime；无法解析返回 None。"""
    if isinstance(value, datetime):
        return value
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None


def updated_categories(outcomes: list[CategoryOutcome]) -> list[str]:
    """执行日志的 categories_updated：只含模板确实变化（或新建）的分类名。"""
    return list(
        dict.fromkeys(
            outcome.category_name
            for outcome in outcomes
            if outcome.changed or outcome.created
        )
    )
