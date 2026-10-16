"""注意事项业务逻辑（spec: task-mgmt Notes attach to a task or to a subtask）。

关键契约：
- 多态目标：target_type ∈ {task, subtask}，目标必须存在（未知 → 404）；
- content 去空白后必须非空，否则 400（NOTE_CONTENT_EMPTY）；
- 删除为物理删除（notes 无外键，删任务/子待办时由各自 service 补删）。
"""

from __future__ import annotations

import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from src.models.note import Note
from src.models.subtask import Subtask
from src.models.task import Task
from src.services.envelope import BadRequest, NotFound, ResultCode


async def _ensure_target_exists(
    session: AsyncSession, target_type: str, target_id: uuid.UUID
) -> None:
    """校验多态目标存在性，按目标类型给出对应的 404 业务码。"""
    if target_type == "task":
        found = await session.get(Task, target_id)
        if found is None:
            raise NotFound(ResultCode.TASK_NOT_FOUND)
    elif target_type == "subtask":
        found = await session.get(Subtask, target_id)
        if found is None:
            raise NotFound(ResultCode.SUBTASK_NOT_FOUND)
    else:
        raise BadRequest(
            ResultCode.BAD_REQUEST_VALIDATION,
            f"未知的注意事项目标类型：{target_type}",
        )


async def create_note(
    session: AsyncSession,
    *,
    target_type: str,
    target_id: uuid.UUID,
    content: str,
) -> Note:
    """创建注意事项：先校验内容非空，再校验目标存在。"""
    text = (content or "").strip()
    if not text:
        raise BadRequest(ResultCode.NOTE_CONTENT_EMPTY)

    await _ensure_target_exists(session, target_type, target_id)

    note = Note(target_type=target_type, target_id=target_id, content=text)
    session.add(note)
    await session.commit()
    await session.refresh(note)
    return note


async def delete_note(session: AsyncSession, note_id: uuid.UUID) -> None:
    """物理删除注意事项；未知 id → 404（NOTE_NOT_FOUND）。"""
    note = await session.get(Note, note_id)
    if note is None:
        raise NotFound(ResultCode.NOTE_NOT_FOUND)
    await session.delete(note)
    await session.commit()
