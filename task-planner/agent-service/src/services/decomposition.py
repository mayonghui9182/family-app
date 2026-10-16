"""会话式任务拆解管线（tasks.md 8.2-8.5，spec ai-decomposition）。

流程：分类 → 模板匹配 → 上下文装配（few-shot + 负载）→ 拆解（2-8 条约束）
→ 经 MCP 落库 draft 提案 → 按序产出 SSE 事件 (事件名, data)。

约定：
- 路由层负责 SSE 编码、总超时与错误兜底；本模块失败一律抛异常
- 分类只用既有分类，无匹配归保留分类「未分类」，绝不创建新分类
- 模板候选仅 is_active=true 的 decomposition；无模板/无匹配走冷启动
- few-shot 与负载获取失败只降级为空上下文，不中断流程
- 落库后失败不做同步回滚：draft 由 24h 过期机制收敛（design.md D5）
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator
from typing import Any

from src.llm.client import LLMClient
from src.llm.prompts import render_prompt
from src.services.mcp_gateway import call_tool

logger = logging.getLogger("agent_service.decomposition")

# 保留分类：AI 归类无匹配时的唯一兜底（绝不新建分类）
RESERVED_CATEGORY = "未分类"
# 子待办数量约束（spec：2-8 条）
MIN_SUBTASKS = 2
MAX_SUBTASKS = 8
# reasoning 分块大小：保证「多块拼接」的 SSE 契约可观测
_REASONING_CHUNK_SIZE = 60


class DecompositionError(Exception):
    """拆解管线业务失败（如重试后子待办仍不足 2 条）。"""


async def decompose(
    message: str, llm: LLMClient
) -> AsyncIterator[tuple[str, dict[str, Any]]]:
    """执行一次拆解，按序产出事件：classification → reasoning×N → proposal → done。

    任何失败抛异常，由路由层统一转成单个 error 事件（文案「拆解失败，请重试」）。
    """
    # ---- 1. 分类：只用既有分类，无匹配归「未分类」 ----
    categories = await call_tool("get_categories") or []
    cls_result = await llm.chat_json(
        system=render_prompt("classification", message=message, categories=categories),
        user=message,
        purpose="classification",
    )
    category_name, confidence = _resolve_classification(cls_result, categories)
    category_id = next(
        (c.get("id") for c in categories if c.get("name") == category_name), None
    )
    logger.info(
        "分类完成 category=%s confidence=%.2f",
        category_name,
        confidence,
        extra={"category": category_name, "confidence": confidence},
    )
    yield "classification", {"category": category_name, "confidence": confidence}

    # ---- 2. 模板匹配：仅启用的 decomposition 模板 ----
    matched_template = await _match_template(llm, message, category_id)

    # ---- 3. 上下文装配：获取失败只降级，不中断 ----
    few_shot_tasks, workload = await asyncio.gather(
        _fetch_few_shot(category_id), _fetch_workload()
    )

    # ---- 4. 拆解：<2 条重试一次；>8 条截断并注明 ----
    result, subtasks = await _decompose_with_retry(
        llm, message, few_shot_tasks, matched_template, workload
    )
    truncated_from = None
    if len(subtasks) > MAX_SUBTASKS:
        truncated_from = len(subtasks)
        subtasks = subtasks[:MAX_SUBTASKS]
    reasoning = _annotate_reasoning(
        str(result.get("agent_reasoning") or ""),
        matched_template,
        workload,
        truncated_from,
    )

    # reasoning 分块产出（SSE 契约：多块拼接为完整推理）
    for chunk in _split_chunks(reasoning):
        yield "reasoning", {"chunk": chunk}

    # ---- 5. 落库：draft + 24h 过期（服务端写入）+ 子待办 AI 标记 ----
    task = await call_tool(
        "create_task",
        {
            "title": str(result.get("task_title") or "").strip() or message,
            "description": str(result.get("task_description") or ""),
            "category_id": category_id,
            "status": "draft",
            "source": "agent",
        },
    )
    task_id = task["id"]
    await call_tool(
        "create_subtasks",
        {
            "task_id": task_id,
            "template_id": matched_template.get("id") if matched_template else None,
            "template_version": matched_template.get("version")
            if matched_template
            else None,
            "subtasks": [
                {
                    "title": item["title"],
                    "description": item["description"],
                    "notes": item["notes"],
                    "sort_order": order,
                }
                for order, item in enumerate(subtasks, start=1)
            ],
        },
    )

    yield (
        "proposal",
        {
            "task_id": task_id,
            "task_title": task.get("title"),
            "task_description": task.get("description") or "",
            "category": category_name,
            "category_confidence": confidence,
            "subtasks": [
                {**item, "sort_order": order}
                for order, item in enumerate(subtasks, start=1)
            ],
            "agent_reasoning": reasoning,
            "expires_at": task.get("expires_at"),
        },
    )
    yield "done", {"task_id": task_id, "status": "draft_created"}


# --------------------------------------------------------------------------
# 分步实现
# --------------------------------------------------------------------------


def _resolve_classification(
    cls_result: dict[str, Any], categories: list[dict[str, Any]]
) -> tuple[str, float]:
    """归类兜底：LLM 选的分类不在既有列表中时归「未分类」；置信度收敛到 [0,1]。"""
    names = {c.get("name") for c in categories}
    name = str(cls_result.get("category") or "").strip()
    if name not in names:
        name = RESERVED_CATEGORY
    try:
        confidence = float(cls_result.get("confidence"))
    except (TypeError, ValueError):
        confidence = 0.5
    return name, min(max(confidence, 0.0), 1.0)


async def _match_template(
    llm: LLMClient, message: str, category_id: str | None
) -> dict[str, Any] | None:
    """模板匹配：仅 is_active=true 的 decomposition 模板；无候选/不匹配返回 None。"""
    if not category_id:
        return None
    templates = (
        await call_tool(
            "get_rule_templates",
            {
                "template_type": "decomposition",
                "category_id": category_id,
                "is_active": True,
            },
        )
        or []
    )
    if not templates:
        return None
    match = await llm.chat_json(
        system=render_prompt(
            "template_match",
            message=message,
            templates=[_template_view(t) for t in templates],
        ),
        user=message,
        purpose="template_match",
    )
    if not match.get("matched"):
        return None
    wanted = str(match.get("template_id") or "")
    # LLM 可能回传不存在的 id：宁可冷启动也不用错模板
    return next((t for t in templates if str(t.get("id")) == wanted), None)


async def _fetch_few_shot(category_id: str | None) -> list[dict[str, Any]]:
    """同分类已完成任务 few-shot；失败/无分类时返回空列表（不中断流程）。"""
    if not category_id:
        return []
    try:
        data = await call_tool(
            "get_completed_tasks_by_category", {"category_id": category_id}
        )
    except Exception as exc:  # 上下文缺失只降级：spec 明确不得中止
        logger.warning("few-shot 获取失败，降级为空历史：%s", exc)
        return []
    return [
        {
            "title": task.get("title", ""),
            "subtasks": [s.get("title", "") for s in task.get("subtasks", [])],
        }
        for task in (data or {}).get("tasks", [])
    ]


async def _fetch_workload() -> dict[str, Any] | None:
    """当前负载；失败返回 None（不中断流程）。附带 high_priority_count 供提醒判定。"""
    try:
        workload = await call_tool("get_current_workload")
    except Exception as exc:
        logger.warning("负载获取失败，降级为无负载上下文：%s", exc)
        return None
    if not workload:
        return None
    distribution = workload.get("priority_distribution") or {}
    return {**workload, "high_priority_count": int(distribution.get("high", 0) or 0)}


async def _decompose_with_retry(
    llm: LLMClient,
    message: str,
    few_shot_tasks: list[dict[str, Any]],
    matched_template: dict[str, Any] | None,
    workload: dict[str, Any] | None,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """拆解调用：子待办 <2 条时重试一次，仍不足抛 DecompositionError。"""
    system = render_prompt(
        "decomposition",
        message=message,
        few_shot_tasks=few_shot_tasks,
        matched_template=_template_view(matched_template) if matched_template else None,
        workload=workload,
    )
    for attempt in (1, 2):
        result = await llm.chat_json(
            system=system, user=message, purpose="decomposition"
        )
        subtasks = _normalize_subtasks(result.get("subtasks"))
        if len(subtasks) >= MIN_SUBTASKS:
            return result, subtasks
        logger.warning("子待办数量不足 count=%d attempt=%d", len(subtasks), attempt)
    raise DecompositionError("重试后子待办仍不足 2 条")


def _normalize_subtasks(raw: Any) -> list[dict[str, Any]]:
    """过滤无效条目：仅保留标题非空的字典项，description/notes 缺省空串。"""
    if not isinstance(raw, list):
        return []
    normalized = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        title = str(item.get("title") or "").strip()
        if not title:
            continue
        normalized.append(
            {
                "title": title,
                "description": str(item.get("description") or ""),
                "notes": str(item.get("notes") or ""),
            }
        )
    return normalized


def _annotate_reasoning(
    reasoning: str,
    matched_template: dict[str, Any] | None,
    workload: dict[str, Any] | None,
    truncated_from: int | None,
) -> str:
    """确定性标注：截断说明、模板参考/冷启动标注、高负载提醒（LLM 未提及时补充）。"""
    parts = [reasoning.strip()] if reasoning.strip() else []
    if truncated_from is not None:
        parts.append(
            f"注意：模型给出 {truncated_from} 条子待办，超过上限 {MAX_SUBTASKS} 条，"
            f"已按顺序截取前 {MAX_SUBTASKS} 条。"
        )
    if matched_template is not None:
        name = str(matched_template.get("name") or "")
        if name and name not in reasoning:
            parts.append(f"参考模板「{name}」但有所调整。")
    elif "无历史参考" not in reasoning:
        parts.append(
            "该分类没有可用的规则模板，本次拆解无历史参考，基于通用能力冷启动完成。"
        )
    high_priority_count = int((workload or {}).get("high_priority_count", 0) or 0)
    if high_priority_count >= 3 and "高优先级" not in reasoning:
        parts.append(
            f"提醒：当前有 {high_priority_count} 个高优先级未完成任务，负载较高，"
            "建议先处理手头任务再安排新任务。"
        )
    return "".join(parts) or "已完成拆解。"


def _split_chunks(text: str) -> list[str]:
    """reasoning 分块：按固定长度切分，至少一块。"""
    return [
        text[i : i + _REASONING_CHUNK_SIZE]
        for i in range(0, len(text), _REASONING_CHUNK_SIZE)
    ]


def _template_view(template: dict[str, Any]) -> dict[str, Any]:
    """MCP 模板行 → prompt 视图：content 内的步骤/数量提升到顶层。"""
    content = template.get("content") or {}
    return {
        "id": str(template.get("id")),
        "name": template.get("name", ""),
        "common_steps": content.get("common_steps") or [],
        "estimated_subtask_count": content.get("estimated_subtask_count"),
        "notes": content.get("notes") or "",
    }
