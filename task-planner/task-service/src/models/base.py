"""SQLAlchemy 声明式基类与统一命名约定。

显式命名约定让 Alembic 迁移中的索引/约束名稳定可预测，
避免出现随机后缀导致 downgrade 与手写迁移对不上。
"""

from __future__ import annotations

from sqlalchemy import MetaData, String
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

# 约束与索引的命名模板
NAMING_CONVENTION = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


class Owned:
    """所有工作业务实体必须具备的真实用户归属。"""

    user_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)


class Base(Owned, DeclarativeBase):
    """所有 ORM 模型的基类。"""

    metadata = MetaData(naming_convention=NAMING_CONVENTION)
