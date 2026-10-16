"""以不可变提案快照采集学习证据，按当前分类和原始模板归组。"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from src.services.mcp_gateway import McpToolError, call_tool

MAX_TASKS_PER_CATEGORY = 10
_CHANGED_LIMIT = 200


def normalize_step(text: Any) -> str:
    """折叠空白，避免排版差异产生学习信号。"""
    return re.sub(r"\s+", " ", str(text or "").strip())


@dataclass
class TaskDiff:
    """原始和当前集合及可验证的用户修改。"""

    task_id: str
    title: str
    ai_steps: list[str] = field(default_factory=list)
    current_steps: list[str] = field(default_factory=list)
    added_steps: list[str] = field(default_factory=list)
    deleted_steps: list[str] = field(default_factory=list)

    @property
    def change_size(self) -> int:
        return len(self.deleted_steps) + len(self.added_steps)

    def to_prompt(self) -> dict[str, Any]:
        return dict(
            task_id=self.task_id,
            title=self.title,
            ai_steps=self.ai_steps,
            current_steps=self.current_steps,
            deleted_steps=self.deleted_steps,
            added_steps=self.added_steps,
        )


@dataclass
class CategoryGroup:
    """分类中属于同一参考模板的样本；冷启动模板为 None。"""

    category_id: str | None
    category_name: str
    template: dict[str, Any] | None
    tasks: list[TaskDiff] = field(default_factory=list)

    def to_prompt(self) -> dict[str, Any]:
        return dict(
            category_name=self.category_name,
            template=self.template,
            tasks=[task.to_prompt() for task in self.tasks],
        )


def flatten_template(row: dict[str, Any]) -> dict[str, Any]:
    content = row.get("content") or {}
    return {
        "id": row.get("id"),
        "name": row.get("name"),
        "version": row.get("version"),
        "category_id": row.get("category_id"),
        "last_manual_edit_at": row.get("last_manual_edit_at"),
        "learning_key": row.get("learning_key"),
        "is_active": row.get("is_active", True),
        "common_steps": [str(s) for s in content.get("common_steps", [])],
        "estimated_subtask_count": int(content.get("estimated_subtask_count") or 0),
        "notes": str(content.get("notes") or ""),
    }


def compute_diff(
    task: dict[str, Any], detail: dict[str, Any], template: dict[str, Any] | None = None
) -> TaskDiff:
    """只比较原始快照，不用当前模板推测已经丢失的历史。"""
    snapshot = detail.get("ai_snapshot")
    result = TaskDiff(str(task.get("id")), str(task.get("title") or ""))
    if not isinstance(snapshot, dict) or not isinstance(snapshot.get("subtasks"), list):
        return result
    result.ai_steps = list(
        dict.fromkeys(
            normalize_step(s.get("title"))
            for s in snapshot["subtasks"]
            if normalize_step(s.get("title"))
        )
    )
    result.current_steps = list(
        dict.fromkeys(
            normalize_step(s.get("title"))
            for s in detail.get("subtasks", [])
            if normalize_step(s.get("title"))
        )
    )
    result.added_steps = [s for s in result.current_steps if s not in result.ai_steps]
    result.deleted_steps = [s for s in result.ai_steps if s not in result.current_steps]
    return result


async def collect_summary_input(
    since: datetime, until: datetime
) -> list[CategoryGroup]:
    """分页读取整个窗口，再按分类选修改最多的十条；取数失败不得假装成功。"""
    changed: list[dict[str, Any]] = []
    offset = 0
    while True:
        page = (
            await call_tool(
                "get_tasks_changed_since",
                {
                    "since": since.isoformat(),
                    "until": until.isoformat(),
                    "limit": _CHANGED_LIMIT,
                    "offset": offset,
                },
            )
            or []
        )
        changed.extend(page)
        if len(page) < _CHANGED_LIMIT:
            break
        offset += len(page)

    templates: dict[str, dict[str, dict]] = {}
    samples: dict[str, list[tuple[TaskDiff, dict, str | None, dict | None]]] = {}
    for task in changed:
        if task.get("status") not in ("pending", "in_progress", "completed"):
            continue
        updated = task.get("updated_at")
        if updated and datetime.fromisoformat(updated.replace("Z", "+00:00")) > until:
            continue
        try:
            detail = await call_tool("get_task_detail", {"task_id": str(task["id"])})
        except McpToolError as exc:
            if exc.code in (40401, 40404):
                continue
            raise
        snapshot = (detail or {}).get("ai_snapshot")
        if not snapshot:
            continue
        diff = compute_diff(task, detail)
        if not diff.change_size:
            continue
        category_id = str(task.get("category_id") or "")
        if category_id not in templates:
            # 数据服务返回该分类全部模板，停用状态须保留用于阻止重建，禁止任取第一个作为归属。
            rows: list[dict[str, Any]] = []
            if category_id:
                while True:
                    page = (
                        await call_tool(
                            "get_rule_templates",
                            {
                                "category_id": category_id,
                                "template_type": "decomposition",
                                "limit": 100,
                                "offset": len(rows),
                            },
                        )
                        or []
                    )
                    rows.extend(page)
                    if len(page) < 100:
                        break
            templates[category_id] = {str(t["id"]): flatten_template(t) for t in rows}
        template_id = snapshot.get("template_id")
        # 改挂分类的任务不能继续更新旧分类模板，以新分类的冷启动证据处理。
        if str(snapshot.get("category_id") or "") != category_id:
            template_id = None
        if not template_id:
            automatic = next(
                (
                    t
                    for t in templates[category_id].values()
                    if t.get("learning_key") == f"cold_start:{category_id}"
                ),
                None,
            )
            template_id = automatic["id"] if automatic else None
        template = templates[category_id].get(str(template_id)) if template_id else None
        if template_id and (template is None or not template["is_active"]):
            # 原模板已停用/不再属于该分类，保留用户停用决策，不新建替身。
            continue
        samples.setdefault(category_id, []).append((diff, task, template_id, template))

    groups: dict[tuple[str, str | None], CategoryGroup] = {}
    for category_id, sample_rows in sorted(samples.items()):
        sample_rows.sort(
            key=lambda row: (
                -row[0].change_size,
                -datetime.fromisoformat(
                    row[1]["updated_at"].replace("Z", "+00:00")
                ).timestamp(),
                row[0].task_id,
            )
        )
        for diff, task, template_id, template in sample_rows[:MAX_TASKS_PER_CATEGORY]:
            key = (category_id, template_id)
            group = groups.setdefault(
                key,
                CategoryGroup(
                    category_id or None,
                    str(task.get("category_name") or "未分类"),
                    template,
                ),
            )
            group.tasks.append(diff)
    return list(groups.values())
