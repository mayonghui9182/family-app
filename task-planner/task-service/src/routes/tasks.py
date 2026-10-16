"""任务 REST 路由（spec: task-mgmt）。

挂载于 `/api` 前缀（src.main）：
- GET    /api/tasks            列表（status/category/source/keyword/due_before 组合过滤）
- GET    /api/tasks/{id}       详情聚合（子待办 + 两级 notes）
- POST   /api/tasks            创建（201）
- PUT    /api/tasks/{id}       部分更新（状态机校验）
- DELETE /api/tasks/{id}       物理删除（级联）

所有响应统一封套 {"code","msg","data"}（design.md D4）。
"""

from __future__ import annotations

import uuid
from datetime import datetime

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from src.db import get_session
from src.schemas.task import TaskCreate, TaskUpdate, task_to_out
from src.services import task_service
from src.services.envelope import ok

router = APIRouter(prefix="/tasks", tags=["tasks"])


@router.get("")
async def list_tasks(
    status: str | None = None,
    category: uuid.UUID | None = None,
    source: str | None = None,
    keyword: str | None = None,
    due_before: datetime | None = None,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """任务列表：过滤条件可组合，created_at 倒序，过期 draft 隐藏。"""
    tasks = await task_service.list_tasks(
        session,
        status=status,
        category_id=category,
        source=source,
        keyword=keyword,
        due_before=due_before,
    )
    return ok([task_to_out(t).model_dump(mode="json") for t in tasks])


@router.get("/{task_id}")
async def get_task(
    task_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """任务详情：聚合子待办（sort_order,id 升序）与两级 notes；未知 id 404。"""
    detail = await task_service.get_task_detail(session, task_id)
    return ok(detail.model_dump(mode="json"))


@router.post("", status_code=201)
async def create_task(
    payload: TaskCreate,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """创建任务：title 必填；缺省 status=pending/priority=medium/source=manual。"""
    task = await task_service.create_task(session, payload)
    return ok(task_to_out(task).model_dump(mode="json"))


@router.put("/{task_id}")
async def update_task(
    task_id: uuid.UUID,
    payload: TaskUpdate,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """部分更新任务：状态流转仅限 5 值状态机白名单；非法流转 400。"""
    task = await task_service.update_task(session, task_id, payload)
    return ok(task_to_out(task).model_dump(mode="json"))


@router.delete("/{task_id}")
async def delete_task(
    task_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """物理删除任务：级联清理子待办与两级 notes；未知 id 404。"""
    await task_service.delete_task(session, task_id)
    return ok(None)
