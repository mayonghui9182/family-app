"""注意事项资源的请求/响应模式（spec: task-mgmt Notes 需求）。"""

from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator

from src.models.note import Note


class NoteCreate(BaseModel):
    """POST /api/tasks/{id}/notes 与 POST /api/subtasks/{id}/notes 请求体。"""

    content: str = Field(min_length=1, description="注意事项内容，必填非空")

    @field_validator("content")
    @classmethod
    def _strip_content(cls, v: str) -> str:
        # 全空白视同空内容，由 service 层以 NOTE_CONTENT_EMPTY(40024) 拒绝
        return v.strip()


class NoteOut(BaseModel):
    """注意事项输出模型。"""

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    target_type: str
    target_id: uuid.UUID
    content: str
    created_at: datetime


def note_to_out(note: Note) -> NoteOut:
    """ORM Note → NoteOut。"""
    return NoteOut.model_validate(note)
