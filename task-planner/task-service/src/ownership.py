"""工作数据库会话的用户归属守卫，拒绝无身份和跨账号访问。"""

from contextvars import ContextVar

from sqlalchemy import event, inspect
from sqlalchemy.orm import Session, with_loader_criteria

from src.models.base import Owned

owner_id: ContextVar[str | None] = ContextVar("work_owner_id", default=None)


def require_owner() -> str:
    """default 仅用于保留旧数据，永远不是可访问身份。"""
    value = owner_id.get()
    if value is None or value == "default":
        raise PermissionError("缺少工作用户作用域")
    return value


@event.listens_for(Session, "do_orm_execute")
def scope_queries(state):
    """查询、关联加载和批量更新删除使用同一个身份条件。"""
    user_id = require_owner()
    if state.is_select:
        state.statement = state.statement.options(with_loader_criteria(
            Owned, lambda cls: cls.user_id == user_id, include_aliases=True))
    elif state.is_update or state.is_delete:
        table = state.statement.table
        if "user_id" not in table.c:
            raise PermissionError("禁止无归属的工作数据修改")
        state.statement = state.statement.where(table.c.user_id == user_id)
    else:
        raise PermissionError("禁止绕过归属的原生数据库操作")


@event.listens_for(Session, "before_flush")
def scope_writes(session, context, instances):
    """新记录赋当前身份，已有记录禁止更换归属。"""
    user_id = require_owner()
    for row in session.new:
        if isinstance(row, Owned):
            if row.user_id not in (None, user_id):
                raise PermissionError("禁止指定其他工作用户")
            row.user_id = user_id
    for row in session.dirty | session.deleted:
        if isinstance(row, Owned):
            history = inspect(row).attrs.user_id.history
            if row.user_id != user_id or any(old != user_id for old in history.deleted):
                raise PermissionError("禁止修改其他工作用户数据")


async def ensure_category(session):
    """每个用户独立未分类，唯一键确保并发初始化幂等。"""
    import uuid

    from sqlalchemy.dialects.postgresql import insert

    from src.models.category import RESERVED_CATEGORY_NAME, Category
    user_id = require_owner()
    connection = await session.connection()
    await connection.execute(insert(Category.__table__).values(
        id=uuid.uuid4(), user_id=user_id, name=RESERVED_CATEGORY_NAME
    ).on_conflict_do_nothing(constraint="uq_categories_owner_name"))
    await session.commit()
