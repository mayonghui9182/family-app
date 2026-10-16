"""分类业务逻辑（spec: task-mgmt Category management with unique names and merge）。

关键契约：
- 列表：按创建时间升序（spec 明确要求 ascending）；
- 名称唯一：创建与重命名均先查重，重名 → 400（CATEGORY_NAME_DUPLICATED）；
- 合并：把源分类下的全部任务与规则模板改挂到目标分类，再删除源分类；
  自合并、目标不存在 → 400（CATEGORY_MERGE_TARGET_INVALID）；
  保留分类「未分类」是 AI 归类兜底，不得被合并掉（40023）。
"""

from __future__ import annotations

import uuid

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from src.models.category import RESERVED_CATEGORY_NAME, Category
from src.models.rule_template import RuleTemplate
from src.models.task import Task
from src.schemas.category import CategoryCreate, CategoryUpdate
from src.services.envelope import BadRequest, NotFound, ResultCode


async def get_category_or_404(session: AsyncSession, category_id: uuid.UUID) -> Category:
    """按主键取分类，不存在抛 404（CATEGORY_NOT_FOUND）。"""
    category = await session.get(Category, category_id)
    if category is None:
        raise NotFound(ResultCode.CATEGORY_NOT_FOUND)
    return category


async def list_categories(session: AsyncSession) -> list[Category]:
    """全量分类，创建时间升序。"""
    result = await session.execute(select(Category).order_by(Category.created_at.asc()))
    return list(result.scalars().all())


async def _ensure_name_available(
    session: AsyncSession, name: str, *, exclude_id: uuid.UUID | None = None
) -> None:
    """名称查重：重名一律 400，重命名时排除自身。"""
    stmt = select(Category.id).where(Category.name == name)
    if exclude_id is not None:
        stmt = stmt.where(Category.id != exclude_id)
    existing = (await session.execute(stmt)).first()
    if existing is not None:
        raise BadRequest(ResultCode.CATEGORY_NAME_DUPLICATED)


async def create_category(session: AsyncSession, payload: CategoryCreate) -> Category:
    """创建分类：名称非空且唯一。"""
    name = payload.name.strip()
    if not name:
        raise BadRequest(ResultCode.BAD_REQUEST_VALIDATION, "分类名称不能为空")
    await _ensure_name_available(session, name)

    category = Category(name=name, description=payload.description)
    session.add(category)
    await session.commit()
    await session.refresh(category)
    return category


async def update_category(
    session: AsyncSession, category_id: uuid.UUID, payload: CategoryUpdate
) -> Category:
    """重命名分类：同样受唯一性约束；未知 id → 404。"""
    category = await get_category_or_404(session, category_id)
    name = payload.name.strip()
    if not name:
        raise BadRequest(ResultCode.BAD_REQUEST_VALIDATION, "分类名称不能为空")
    await _ensure_name_available(session, name, exclude_id=category.id)

    if category.name == RESERVED_CATEGORY_NAME and name != RESERVED_CATEGORY_NAME:
        raise BadRequest(ResultCode.CATEGORY_RESERVED_UNDELETABLE)
    category.name = name
    category.description = payload.description
    await session.commit()
    await session.refresh(category)
    return category


async def merge_category(
    session: AsyncSession, source_id: uuid.UUID, target_id: uuid.UUID
) -> Category:
    """把源分类的任务与规则模板改挂到目标分类，随后删除源分类。

    返回目标分类，便于调用方直接展示合并结果。
    """
    source = await get_category_or_404(session, source_id)

    if source_id == target_id:
        raise BadRequest(
            ResultCode.CATEGORY_MERGE_TARGET_INVALID, "不能把分类合并到它自己"
        )
    if source.name == RESERVED_CATEGORY_NAME:
        # 保留分类是 AI 无匹配时的兜底归属，合并会导致它被删除
        raise BadRequest(ResultCode.CATEGORY_RESERVED_UNDELETABLE)

    target = await session.get(Category, target_id)
    if target is None:
        raise BadRequest(
            ResultCode.CATEGORY_MERGE_TARGET_INVALID, "合并目标分类不存在"
        )

    # 改挂两张引用表：任务与规则模板（否则外键约束会阻止删除源分类）
    await session.execute(
        update(Task).where(Task.category_id == source.id).values(category_id=target.id)
    )
    await session.execute(
        update(RuleTemplate)
        .where(RuleTemplate.category_id == source.id)
        .values(category_id=target.id)
    )

    await session.delete(source)
    await session.commit()
    await session.refresh(target)
    return target
