"""notes — 注意事项表（设计文档 §3）。

多态关联：target_type + target_id 指向 tasks.id 或 subtasks.id，
不建数据库外键，级联删除由应用层保证（删除任务/子待办时同步清理）。
"""

import uuid
from datetime import datetime

from sqlalchemy import CheckConstraint, DateTime, Index, String, Text, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from src.models.base import Base
from src.models.enums import NOTE_TARGET_TYPES, in_clause


class Note(Base):
    """注意事项：挂在任务或子待办上，content 必填非空。"""

    __tablename__ = "notes"
    __table_args__ = (
        CheckConstraint(in_clause("target_type", NOTE_TARGET_TYPES), name="target_type_enum"),
        # 多态目标查询恒以 (target_type, target_id) 组合过滤，建复合索引
        Index("ix_notes_target_type_target_id", "target_type", "target_id"),
    )

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4, comment="主键 UUID"
    )
    target_type: Mapped[str] = mapped_column(
        String(10), nullable=False, comment="目标类型：task/subtask"
    )
    target_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), nullable=False,
        comment="目标 ID，指向 tasks.id 或 subtasks.id（多态，无外键）",
    )
    content: Mapped[str] = mapped_column(Text, nullable=False, comment="注意事项内容，非空")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), comment="创建时间"
    )
