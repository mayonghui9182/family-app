"""子待办业务逻辑（REST 与 MCP 共用，spec: task-mgmt Subtask management）。

关键契约：
- 创建：父任务必须存在（未知 → 404 TASK_NOT_FOUND）；默认 sort_order=0、status=pending；
  `is_ai_generated` 由调用方决定 —— REST 手动创建恒 false，仅 agent 经 MCP 批量创建时为 true；
- 更新：状态流转复用与任务相同的 5 值白名单（终态不可复活）；updated_at 必刷新；
- 删除：物理删除，并补删挂在该子待办上的 notes（notes 无数据库外键）。
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

from sqlalchemy import delete
from sqlalchemy.ext.asyncio import AsyncSession

from src.models.enums import is_valid_transition
from src.models.note import Note
from src.models.subtask import Subtask
from src.models.task import Task
from src.schemas.subtask import SubtaskCreate, SubtaskUpdate
from src.services.envelope import (
    BadRequest,
    InvalidStatusTransition,
    NotFound,
    ResultCode,
)
from src.services.task_service import get_task_or_404


def _now() -> datetime:
    """统一取当前 UTC 时间（与数据库 timezone-aware 列对齐）。"""
    return datetime.now(UTC)


async def get_subtask_or_404(session: AsyncSession, subtask_id: uuid.UUID) -> Subtask:
    """按主键取子待办，不存在抛 404（SUBTASK_NOT_FOUND）。"""
    subtask = await session.get(Subtask, subtask_id)
    if subtask is None:
        raise NotFound(ResultCode.SUBTASK_NOT_FOUND)
    return subtask


async def _touch_parent(session: AsyncSession, task_id: uuid.UUID | None) -> None:
    """刷新父任务 `updated_at`：让子待办的增删改能被任务级时间窗口查询捕获。

    每日总结（agent-service）按任务的 `created_at/updated_at` 圈定当天窗口，
    子待办删除是物理删除、不留痕迹，只能靠父任务时间戳把这条修改证据带出来。
    """
    if task_id is None:
        return
    task = await session.get(Task, task_id)
    if task is not None:
        task.updated_at = _now()


async def create_subtask(
    session: AsyncSession,
    task_id: uuid.UUID,
    payload: SubtaskCreate,
    *,
    is_ai_generated: bool = False,
) -> Subtask:
    """在指定任务下创建子待办；未知父任务 → 404。"""
    task = await get_task_or_404(session, task_id)

    subtask = Subtask(
        parent_task_id=task_id,
        title=payload.title,
        description=payload.description,
        sort_order=payload.sort_order,
        status=payload.status,
        is_ai_generated=is_ai_generated,
    )
    session.add(subtask)
    # 用户新增子待办是学习信号之一，需让父任务出现在当天窗口内
    task.updated_at = _now()
    await session.commit()
    await session.refresh(subtask)
    return subtask


async def update_subtask(
    session: AsyncSession, subtask_id: uuid.UUID, payload: SubtaskUpdate
) -> Subtask:
    """部分更新子待办：状态机白名单校验 + updated_at 刷新。"""
    subtask = await get_subtask_or_404(session, subtask_id)
    data = payload.model_dump(exclude_unset=True)

    if "title" in data:
        title = (data["title"] or "").strip()
        if not title:
            raise BadRequest(ResultCode.BAD_REQUEST_TITLE_EMPTY)
        subtask.title = title
    if "description" in data:
        subtask.description = data["description"]
    if "sort_order" in data and data["sort_order"] is not None:
        subtask.sort_order = data["sort_order"]

    if "status" in data and data["status"] is not None:
        target = data["status"]
        if target != subtask.status:
            # 与任务共用 5 值状态机：白名单外一律拒绝
            if not is_valid_transition(subtask.status, target):
                raise InvalidStatusTransition(
                    f"子待办状态不能从 {subtask.status} 切换到 {target}"
                )
            subtask.status = target
        # 目标状态与当前相同：视为幂等 no-op，不做流转校验

    subtask.updated_at = _now()
    await _touch_parent(session, subtask.parent_task_id)
    await session.commit()
    await session.refresh(subtask)
    return subtask


async def delete_subtask(session: AsyncSession, subtask_id: uuid.UUID) -> None:
    """物理删除子待办，并清理其名下 notes。"""
    subtask = await get_subtask_or_404(session, subtask_id)
    parent_task_id = subtask.parent_task_id

    await session.execute(
        delete(Note).where(
            Note.target_type == "subtask", Note.target_id == subtask.id
        )
    )
    await session.delete(subtask)
    # 删除后子待办行不再存在，只能靠父任务时间戳保留「用户删掉了这一步」的证据
    await _touch_parent(session, parent_task_id)
    await session.commit()
