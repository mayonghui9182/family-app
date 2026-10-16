"""子待办 REST 路由（spec: task-mgmt Subtask management）。

挂载于 `/api` 前缀（src.main）。两类路径共存，故本路由不设 prefix：
- POST   /api/tasks/{task_id}/subtasks    在任务下创建子待办（201）
- PUT    /api/subtasks/{subtask_id}       部分更新（复用 5 值状态机）
- DELETE /api/subtasks/{subtask_id}       物理删除并级联清理其 notes

所有响应统一封套 {"code","msg","data"}（design.md D4）。
"""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from src.db import get_session
from src.schemas.subtask import SubtaskCreate, SubtaskUpdate
from src.schemas.task import subtask_to_out
from src.services import subtask_service
from src.services.envelope import ok

router = APIRouter(tags=["subtasks"])


@router.post("/tasks/{task_id}/subtasks", status_code=201)
async def create_subtask(
    task_id: uuid.UUID,
    payload: SubtaskCreate,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """创建子待办：REST 手动创建 is_ai_generated 恒 false；未知父任务 404。"""
    subtask = await subtask_service.create_subtask(session, task_id, payload)
    return ok(subtask_to_out(subtask).model_dump(mode="json"))


@router.put("/subtasks/{subtask_id}")
async def update_subtask(
    subtask_id: uuid.UUID,
    payload: SubtaskUpdate,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """部分更新子待办：非法状态流转 400；未知 id 404。"""
    subtask = await subtask_service.update_subtask(session, subtask_id, payload)
    return ok(subtask_to_out(subtask).model_dump(mode="json"))


@router.delete("/subtasks/{subtask_id}")
async def delete_subtask(
    subtask_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """物理删除子待办，并清理其名下注意事项；未知 id 404。"""
    await subtask_service.delete_subtask(session, subtask_id)
    return ok(None)
