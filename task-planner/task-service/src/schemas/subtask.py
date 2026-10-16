"""子待办资源的请求模式（spec: task-mgmt Subtask management）。

约定：
- 创建：title 必填非空，sort_order 默认 0、status 默认 pending；
  is_ai_generated 不由 REST 请求体控制（手动创建恒 false，仅 agent 经 MCP 置 true）；
- 更新：全部字段可选，status 复用与任务相同的 5 值状态机白名单。
"""

from __future__ import annotations

from pydantic import BaseModel, Field, field_validator

from src.schemas.common import check_status, normalize_title


class SubtaskCreate(BaseModel):
    """POST /api/tasks/{id}/subtasks 请求体。"""

    title: str = Field(min_length=1, max_length=255, description="子待办标题，必填非空")
    description: str | None = None
    sort_order: int = Field(default=0, description="排序序号，默认 0")
    status: str = Field(default="pending", description="初始状态，默认 pending")

    _v_title = field_validator("title")(normalize_title)
    _v_status = field_validator("status")(check_status)


class SubtaskUpdate(BaseModel):
    """PUT /api/subtasks/{id} 请求体：部分更新，仅处理显式传入的字段。"""

    title: str | None = Field(default=None, max_length=255)
    description: str | None = None
    sort_order: int | None = None
    status: str | None = None

    _v_title = field_validator("title")(normalize_title)
    _v_status = field_validator("status")(check_status)
