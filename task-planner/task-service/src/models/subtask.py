"""subtasks — 子待办表（设计文档 §3）。

`is_ai_generated` 是对比学习的关键标记（design.md D7）：
仅 agent 经 MCP 创建时为 true，REST 手动创建恒为 false。
"""

import uuid
from datetime import datetime

from sqlalchemy import Boolean, CheckConstraint, DateTime, ForeignKey, Integer, String, Text, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from src.models.base import Base
from src.models.enums import TASK_STATUSES, in_clause


class Subtask(Base):
    """子待办：隶属任务，状态机与任务共用 5 值白名单。"""

    __tablename__ = "subtasks"
    __table_args__ = (
        CheckConstraint(in_clause("status", TASK_STATUSES), name="status_enum"),
    )

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4, comment="主键 UUID"
    )
    parent_task_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("tasks.id", ondelete="CASCADE"),
        nullable=False, index=True, comment="所属任务，任务删除时级联删除",
    )
    title: Mapped[str] = mapped_column(String(255), nullable=False, comment="子待办标题，必填")
    description: Mapped[str | None] = mapped_column(Text, nullable=True, comment="子待办描述")
    sort_order: Mapped[int] = mapped_column(
        Integer, nullable=False, default=0, server_default="0", comment="排序序号"
    )
    status: Mapped[str] = mapped_column(
        String(20), nullable=False, default="pending", server_default="pending",
        comment="5 值状态，与任务共用状态机白名单",
    )
    is_ai_generated: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=False, server_default="false",
        comment="是否 AI 生成（对比学习关键标记）",
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), comment="创建时间"
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now(),
        comment="更新时间",
    )

    # 所属任务（反向引用，级联删除由 Task.subtasks 与 DB ondelete 共同保证）
    parent_task: Mapped["Task"] = relationship("Task", back_populates="subtasks")  # noqa: F821
