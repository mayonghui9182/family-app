"""任务资源的请求/响应模式（spec: task-mgmt）。

约定：
- 创建：title 必填非空（strip 后），status/priority 限定枚举取值集合；
- 更新：全部字段可选，用 `exclude_unset` 区分「未传」与「显式置 null」；
- 输出：TaskOut 列表项 / TaskDetailOut 详情聚合（子待办按 sort_order,id 升序，
  子待办各自携带 notes，任务级 notes 单独成组）。
"""

from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator

from src.models.note import Note
from src.models.subtask import Subtask
from src.models.task import Task
from src.schemas.common import check_priority, check_status, normalize_title
from src.schemas.note import NoteOut


class TaskCreate(BaseModel):
    """POST /api/tasks 请求体：title 必填，其余可选并套用默认值。"""

    title: str = Field(min_length=1, max_length=255, description="任务标题，必填非空")
    description: str | None = None
    category_id: uuid.UUID | None = None
    status: str = Field(default="pending", description="初始状态，默认 pending")
    priority: str = Field(default="medium", description="优先级，默认 medium")
    due_date: datetime | None = None

    _v_title = field_validator("title")(normalize_title)
    _v_status = field_validator("status")(check_status)
    _v_priority = field_validator("priority")(check_priority)


class TaskUpdate(BaseModel):
    """PUT /api/tasks/{id} 请求体：部分更新，仅处理显式传入的字段。"""

    title: str | None = Field(default=None, max_length=255)
    description: str | None = None
    category_id: uuid.UUID | None = None
    status: str | None = None
    priority: str | None = None
    due_date: datetime | None = None

    _v_title = field_validator("title")(normalize_title)
    _v_status = field_validator("status")(check_status)
    _v_priority = field_validator("priority")(check_priority)


class SubtaskOut(BaseModel):
    """子待办输出模型（列表/创建场景，不带 notes）。"""

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    title: str
    description: str | None = None
    sort_order: int
    status: str
    is_ai_generated: bool
    created_at: datetime
    updated_at: datetime


class SubtaskDetailOut(SubtaskOut):
    """子待办详情输出模型：附带指向该子待办的全部 notes。"""

    notes: list[NoteOut] = Field(default_factory=list)


class TaskOut(BaseModel):
    """任务列表项/写操作响应模型。"""

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    user_id: str
    title: str
    description: str | None = None
    category_id: uuid.UUID | None = None
    category_name: str | None = None
    status: str
    priority: str
    due_date: datetime | None = None
    parent_task_id: uuid.UUID | None = None
    expires_at: datetime | None = None
    source: str
    created_at: datetime
    updated_at: datetime


class TaskDetailOut(TaskOut):
    """任务详情响应模型：聚合子待办（各带 notes）与任务级 notes。"""

    ai_snapshot: dict | None = None
    subtasks: list[SubtaskDetailOut] = Field(default_factory=list)
    notes: list[NoteOut] = Field(default_factory=list)


def task_to_out(task: Task) -> TaskOut:
    """ORM Task → TaskOut（补充分类名，selectin 已预加载 category）。"""
    out = TaskOut.model_validate(task)
    out.category_name = task.category.name if task.category else None
    return out


def task_to_detail_out(
    task: Task,
    task_notes: list[Note],
    subtask_notes: dict[uuid.UUID, list[Note]],
) -> TaskDetailOut:
    """ORM Task + 已查询的 notes → TaskDetailOut。

    subtasks 顺序由 relationship 的 order_by（sort_order,id）保证；
    每个子待办从 subtask_notes 映射中取自己的 notes。
    """
    out = TaskDetailOut.model_validate(task)
    out.category_name = task.category.name if task.category else None
    out.notes = [NoteOut.model_validate(n) for n in task_notes]
    details: list[SubtaskDetailOut] = []
    for st in task.subtasks:
        st_out = SubtaskDetailOut.model_validate(st)
        st_out.notes = [NoteOut.model_validate(n) for n in subtask_notes.get(st.id, [])]
        details.append(st_out)
    out.subtasks = details
    return out


def subtask_to_out(subtask: Subtask) -> SubtaskOut:
    """ORM Subtask → SubtaskOut（组 4 子待办 REST 复用）。"""
    return SubtaskOut.model_validate(subtask)
