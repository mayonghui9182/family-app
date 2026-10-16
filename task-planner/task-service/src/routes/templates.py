"""模板与版本历史 REST 路由（spec: template-learning）。

挂载于 `/api` 前缀（src.main）：
- GET /api/templates                            列表（category/type 可组合过滤，
                                                另支持 is_active 供 agent 候选匹配）
- POST /api/templates                           创建（version=1，201）
- PUT /api/templates/{id}                       编辑（content 变化 → version+1 + 快照行）
- GET /api/templates/{id}/versions              版本历史（version 倒序）
- PUT /api/templates/{id}/versions/{version_id} 切换版本重要标记

所有响应统一封套 {"code","msg","data"}（design.md D4）。
"""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from src.db import get_session
from src.schemas.template import (
    TemplateCreate,
    TemplateTypeFilter,
    TemplateUpdate,
    VersionImportantUpdate,
    template_to_out,
    template_version_to_out,
)
from src.services import template_service
from src.services.envelope import ok

router = APIRouter(prefix="/templates", tags=["templates"])


@router.get("")
async def list_templates(
    type: TemplateTypeFilter | None = Query(default=None, description="模板类型过滤"),
    category: uuid.UUID | None = Query(default=None, description="分类 id 过滤"),
    is_active: bool | None = Query(default=None, description="启用状态过滤（agent 候选用）"),
    session: AsyncSession = Depends(get_session),
) -> dict:
    """模板列表：category/type 可组合过滤，created_at 升序。"""
    templates = await template_service.list_templates(
        session, template_type=type, category_id=category, is_active=is_active
    )
    return ok([template_to_out(t).model_dump(mode="json") for t in templates])


@router.post("", status_code=201)
async def create_template(
    payload: TemplateCreate,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """创建模板：version=1、is_active=true，并写入版本 1 快照行。"""
    template = await template_service.create_template(session, payload)
    return ok(template_to_out(template).model_dump(mode="json"))


@router.put("/{template_id}")
async def update_template(
    template_id: uuid.UUID,
    payload: TemplateUpdate,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """编辑模板：content 变化触发 version+1 与「用户手动编辑」快照；未知 id 404。"""
    template = await template_service.update_template(session, template_id, payload)
    return ok(template_to_out(template).model_dump(mode="json"))


@router.get("/{template_id}/versions")
async def list_template_versions(
    template_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """版本历史：按 version 倒序返回全部快照行。"""
    versions = await template_service.list_versions(session, template_id)
    return ok([template_version_to_out(v).model_dump(mode="json") for v in versions])


@router.put("/{template_id}/versions/{version_id}")
async def mark_version_important(
    template_id: uuid.UUID,
    version_id: uuid.UUID,
    payload: VersionImportantUpdate,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """切换版本重要标记：is_important=true 的行永不被保留策略自动删除。"""
    version = await template_service.set_version_important(
        session, template_id, version_id, payload.is_important
    )
    return ok(template_version_to_out(version).model_dump(mode="json"))
