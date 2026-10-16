"""分类 REST 路由（spec: task-mgmt Category management with unique names and merge）。

挂载于 `/api` 前缀（src.main）：
- GET  /api/categories                 全量分类，创建时间升序
- POST /api/categories                 创建（名称唯一，201）
- PUT  /api/categories/{id}            重命名（同受唯一性约束）
- POST /api/categories/{id}/merge      合并到目标分类：改挂任务与规则模板后删除源分类

所有响应统一封套 {"code","msg","data"}（design.md D4）。
"""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from src.db import get_session
from src.schemas.category import (
    CategoryCreate,
    CategoryMergeRequest,
    CategoryUpdate,
    category_to_out,
)
from src.services import category_service
from src.services.envelope import ok

router = APIRouter(prefix="/categories", tags=["categories"])


@router.get("")
async def list_categories(session: AsyncSession = Depends(get_session)) -> dict:
    """分类列表：按创建时间升序返回全部分类。"""
    categories = await category_service.list_categories(session)
    return ok([category_to_out(c).model_dump(mode="json") for c in categories])


@router.post("", status_code=201)
async def create_category(
    payload: CategoryCreate,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """创建分类：名称必填非空且唯一，重名 400。"""
    category = await category_service.create_category(session, payload)
    return ok(category_to_out(category).model_dump(mode="json"))


@router.put("/{category_id}")
async def update_category(
    category_id: uuid.UUID,
    payload: CategoryUpdate,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """重命名分类：未知 id 404，与其他分类重名 400。"""
    category = await category_service.update_category(session, category_id, payload)
    return ok(category_to_out(category).model_dump(mode="json"))


@router.post("/{category_id}/merge")
async def merge_category(
    category_id: uuid.UUID,
    payload: CategoryMergeRequest,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """合并分类：源分类的任务与规则模板改挂目标后删除源分类；自合并/未知目标 400。"""
    target = await category_service.merge_category(
        session, category_id, payload.target_category_id
    )
    return ok(category_to_out(target).model_dump(mode="json"))
