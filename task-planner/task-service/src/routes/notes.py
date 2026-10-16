"""注意事项 REST 路由（spec: task-mgmt Notes attach to a task or to a subtask）。

挂载于 `/api` 前缀（src.main）。任务级与子待办级两个创建入口路径不同，
故本路由不设 prefix：
- POST   /api/tasks/{task_id}/notes        创建任务级注意事项（target_type=task）
- POST   /api/subtasks/{subtask_id}/notes  创建子待办级注意事项（target_type=subtask）
- DELETE /api/notes/{note_id}              物理删除

所有响应统一封套 {"code","msg","data"}（design.md D4）。
"""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from src.db import get_session
from src.schemas.note import NoteCreate, note_to_out
from src.services import note_service
from src.services.envelope import ok

router = APIRouter(tags=["notes"])


@router.post("/tasks/{task_id}/notes", status_code=201)
async def create_task_note(
    task_id: uuid.UUID,
    payload: NoteCreate,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """任务级注意事项：content 必填非空；未知任务 404。"""
    note = await note_service.create_note(
        session, target_type="task", target_id=task_id, content=payload.content
    )
    return ok(note_to_out(note).model_dump(mode="json"))


@router.post("/subtasks/{subtask_id}/notes", status_code=201)
async def create_subtask_note(
    subtask_id: uuid.UUID,
    payload: NoteCreate,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """子待办级注意事项：content 必填非空；未知子待办 404。"""
    note = await note_service.create_note(
        session, target_type="subtask", target_id=subtask_id, content=payload.content
    )
    return ok(note_to_out(note).model_dump(mode="json"))


@router.delete("/notes/{note_id}")
async def delete_note(
    note_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """物理删除注意事项；未知 id 404。"""
    await note_service.delete_note(session, note_id)
    return ok(None)
