"""tasks — 任务表（设计文档 §3）。

核心业务实体：5 值状态机作用于 status；draft 提案携带 expires_at（24h 过期）；
user_id 为 MVP 固定值 "default"（预留多用户）。
"""

import uuid
from datetime import datetime

from sqlalchemy import CheckConstraint, DateTime, ForeignKey, String, Text, func
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from src.models.base import Base
from src.models.enums import PRIORITIES, TASK_SOURCES, TASK_STATUSES, in_clause


class Task(Base):
    """任务：状态机 5 值固定，来源区分 manual/agent 等渠道。"""

    __tablename__ = "tasks"
    __table_args__ = (
        CheckConstraint(in_clause("status", TASK_STATUSES), name="status_enum"),
        CheckConstraint(in_clause("priority", PRIORITIES), name="priority_enum"),
        CheckConstraint(in_clause("source", TASK_SOURCES), name="source_enum"),
    )

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4, comment="主键 UUID"
    )
    ai_snapshot: Mapped[dict | None] = mapped_column(
        JSONB, nullable=True, comment="不可变的原始提案，含模板归属和子待办快照"
    )
    title: Mapped[str] = mapped_column(
        String(255), nullable=False, comment="任务标题，必填"
    )
    description: Mapped[str | None] = mapped_column(
        Text, nullable=True, comment="任务描述"
    )
    category_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("categories.id"),
        nullable=True,
        index=True,
        comment="所属分类",
    )
    status: Mapped[str] = mapped_column(
        String(20),
        nullable=False,
        default="pending",
        server_default="pending",
        index=True,
        comment="5 值状态：draft/pending/in_progress/completed/cancelled",
    )
    priority: Mapped[str] = mapped_column(
        String(10),
        nullable=False,
        default="medium",
        server_default="medium",
        comment="优先级：high/medium/low",
    )
    due_date: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True, comment="截止时间"
    )
    parent_task_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("tasks.id"),
        nullable=True,
        comment="父任务自引用（预留，MVP 子待办走 subtasks 表）",
    )
    expires_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
        index=True,
        comment="draft 专用过期时间（创建时间 + 24h），其他状态必须为 null",
    )
    source: Mapped[str] = mapped_column(
        String(20),
        nullable=False,
        default="manual",
        server_default="manual",
        comment="来源：manual/agent/routine/schedule/trigger",
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        index=True,
        comment="创建时间",
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        onupdate=func.now(),
        comment="更新时间，每次成功更新刷新",
    )

    # 子待办：数据库级 CASCADE + ORM 级 delete-orphan 双保险；
    # 排序固定 sort_order,id（spec 详情接口契约），selectin 避免懒加载并发问题
    subtasks: Mapped[list["Subtask"]] = relationship(  # noqa: F821
        "Subtask",
        back_populates="parent_task",
        cascade="all, delete-orphan",
        passive_deletes=True,
        order_by="Subtask.sort_order, Subtask.id",
        lazy="selectin",
    )
    # 所属分类：只读关联，列表/详情需要分类名
    category: Mapped["Category | None"] = relationship("Category", lazy="selectin")  # noqa: F821
