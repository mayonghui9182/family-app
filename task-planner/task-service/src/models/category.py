"""categories — 分类表（设计文档 §3）。

`未分类` 为保留分类（design.md D11）：迁移 seed 写入，分类合并与
AI 归类兜底均依赖它，业务代码不得删除该分类。
"""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, String, Text, UniqueConstraint, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from src.models.base import Base

# 保留分类名（迁移 seed 与 AI 归类兜底共用）
RESERVED_CATEGORY_NAME = "未分类"


class Category(Base):
    """分类：任务与规则模板的挂载点，名称全局唯一。"""

    __tablename__ = "categories"
    __table_args__ = (UniqueConstraint("user_id", "name", name="uq_categories_owner_name"),)

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4, comment="主键 UUID"
    )
    name: Mapped[str] = mapped_column(String(64), nullable=False, comment="当前用户内分类名唯一")
    description: Mapped[str | None] = mapped_column(Text, nullable=True, comment="分类描述")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), comment="创建时间"
    )
