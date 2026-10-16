"""跨资源复用的 pydantic 字段校验器。"""

from __future__ import annotations

from src.models.enums import PRIORITIES, TASK_STATUSES


def check_status(v: str | None) -> str | None:
    """状态取值必须在 5 值集合内（硬约束，任务与子待办共用）。"""
    if v is not None and v not in TASK_STATUSES:
        raise ValueError(f"status 必须是 {TASK_STATUSES} 之一")
    return v


def check_priority(v: str | None) -> str | None:
    """优先级取值必须在枚举集合内。"""
    if v is not None and v not in PRIORITIES:
        raise ValueError(f"priority 必须是 {PRIORITIES} 之一")
    return v


def normalize_title(v: str | None) -> str | None:
    """标题去首尾空白后必须非空（spec：title required, non-empty）。"""
    if v is None:
        return None
    v = v.strip()
    if not v:
        raise ValueError("标题不能为空")
    return v
