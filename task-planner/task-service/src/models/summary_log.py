"""summary_execution_log — 每日总结执行记录表（设计文档 §3）。

每日总结（22:00 自动 / 手动触发）每次运行写一条：状态、错误信息、
本次更新的分类清单、触发来源。过期 draft 批量 cancelled 也随该运行执行。
"""

import uuid
from datetime import datetime

from sqlalchemy import CheckConstraint, DateTime, String, Text, func
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from src.models.base import Base
from src.models.enums import SUMMARY_STATUSES, SUMMARY_TRIGGER_SOURCES, in_clause


class SummaryExecutionLog(Base):
    """每日总结执行日志：一次运行一条记录。"""

    __tablename__ = "summary_execution_log"
    __table_args__ = (
        CheckConstraint(in_clause("status", SUMMARY_STATUSES), name="status_enum"),
        CheckConstraint(in_clause("trigger_source", SUMMARY_TRIGGER_SOURCES), name="trigger_source_enum"),
    )

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4, comment="主键 UUID"
    )
    run_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), comment="运行时间"
    )
    status: Mapped[str] = mapped_column(
        String(10), nullable=False, comment="执行状态：success/failed"
    )
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True, comment="失败原因")
    categories_updated: Mapped[list | None] = mapped_column(
        JSONB, nullable=True, comment="本次更新的分类名列表，如 [\"市场推广\"]"
    )
    trigger_source: Mapped[str] = mapped_column(
        String(10), nullable=False, default="auto", server_default="auto",
        comment="触发来源：auto（22:00 定时）/manual（手动）",
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), comment="创建时间"
    )
