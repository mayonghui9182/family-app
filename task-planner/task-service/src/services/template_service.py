"""模板与版本历史业务逻辑（spec: template-learning）。

关键契约：
- 列表：category/type 可组合过滤（另提供 is_active 过滤，供 agent 候选匹配），
  created_at 升序；MVP 仅 decomposition 有行为，routine 只存不评估；
- 创建：version=1 并写入版本 1 快照行（change_summary 默认「创建模板」，
  每日总结自动创建时由调用方传入「每日总结自动创建」）；
- content 变更 → version+1 + 整份快照行（parent_version_id 指向上一版本行）；
  手动编辑的 change_summary 固定「用户手动编辑」，快照行的 created_at 即
  手动编辑时间（组 9 当天冲突判定以此为准）；仅切换 is_active 不产生版本；
- 保留策略：每模板最多 MAX_TEMPLATE_VERSIONS(20) 行，超量时从最老的非重要
  版本开始淘汰，is_important=true 的行永不自动删除。
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from src.models.category import Category
from src.models.rule_template import (
    MAX_TEMPLATE_VERSIONS,
    RuleTemplate,
    RuleTemplateVersion,
)
from src.schemas.template import TemplateCreate, TemplateUpdate
from src.services.envelope import BadRequest, Conflict, NotFound, ResultCode

# 手动编辑版本行的变更摘要（spec 固定文案，逐字一致）
MANUAL_EDIT_SUMMARY = "用户手动编辑"
# REST 手动创建模板的版本摘要（spec 未约束，留默认值供追溯）
CREATE_SUMMARY = "创建模板"


def _now() -> datetime:
    """统一取当前 UTC 时间（与数据库 timezone-aware 列对齐）。"""
    return datetime.now(UTC)


async def get_template_or_404(
    session: AsyncSession, template_id: uuid.UUID
) -> RuleTemplate:
    """按主键取模板，不存在抛 404（TEMPLATE_NOT_FOUND）。"""
    template = await session.get(RuleTemplate, template_id)
    if template is None:
        raise NotFound(ResultCode.TEMPLATE_NOT_FOUND)
    return template


async def _ensure_category_exists(
    session: AsyncSession, category_id: uuid.UUID | None
) -> None:
    """校验分类存在性：未知 category_id 与任务创建一致，按 400 拒绝。"""
    if category_id is None:
        return
    category = await session.get(Category, category_id)
    if category is None:
        raise BadRequest(ResultCode.BAD_REQUEST_VALIDATION, "分类不存在")


async def list_templates(
    session: AsyncSession,
    *,
    template_type: str | None = None,
    category_id: uuid.UUID | None = None,
    is_active: bool | None = None,
    offset: int = 0,
    limit: int | None = None,
) -> list[RuleTemplate]:
    """组合过滤查询模板列表，created_at 升序。"""
    stmt = select(RuleTemplate)
    if template_type is not None:
        stmt = stmt.where(RuleTemplate.type == template_type)
    if category_id is not None:
        stmt = stmt.where(RuleTemplate.category_id == category_id)
    if is_active is not None:
        stmt = stmt.where(RuleTemplate.is_active == is_active)
    stmt = stmt.order_by(RuleTemplate.created_at.asc(), RuleTemplate.id)
    stmt = stmt.offset(max(0, offset))
    if limit is not None:
        stmt = stmt.limit(limit)
    result = await session.execute(stmt)
    return list(result.scalars().all())


async def _latest_version_row(
    session: AsyncSession, template_id: uuid.UUID
) -> RuleTemplateVersion | None:
    """取模板当前最高版本号的快照行（新版本的 parent_version_id 指向它）。"""
    result = await session.execute(
        select(RuleTemplateVersion)
        .where(RuleTemplateVersion.template_id == template_id)
        .order_by(RuleTemplateVersion.version.desc())
        .limit(1)
    )
    return result.scalars().first()


async def _apply_retention(session: AsyncSession, template: RuleTemplate) -> None:
    """版本保留策略：只留最近 MAX_TEMPLATE_VERSIONS 行。

    淘汰顺序为「版本号最老的非重要版本优先」；is_important=true 的行与
    当前版本行（刚写入的快照）永不自动删除——若旧行全部受保护，
    宁可暂时超出上限也不淘汰（防御性契约）。
    删除前先断开两类指向被删行的引用，避免外键约束报错：
    - 其他版本行的 parent_version_id（自引用外键，无 ondelete）；
    - 模板主表的 parent_version_id（循环外键）。
    """
    result = await session.execute(
        select(RuleTemplateVersion)
        .where(RuleTemplateVersion.template_id == template.id)
        .order_by(RuleTemplateVersion.version.asc())
    )
    rows = list(result.scalars().all())
    excess = len(rows) - MAX_TEMPLATE_VERSIONS
    if excess <= 0:
        return

    for row in rows:
        if excess <= 0:
            break
        if row.is_important or row.version == template.version:
            # 重要版本与当前版本永不自动删除
            continue
        await session.execute(
            update(RuleTemplateVersion)
            .where(RuleTemplateVersion.parent_version_id == row.id)
            .values(parent_version_id=None)
        )
        if template.parent_version_id == row.id:
            template.parent_version_id = None
        await session.delete(row)
        excess -= 1


async def _append_version(
    session: AsyncSession, template: RuleTemplate, *, change_summary: str
) -> RuleTemplateVersion:
    """为模板当前 content 写整份快照行，并执行保留策略（调用方负责 commit）。"""
    previous = await _latest_version_row(session, template.id)
    row = RuleTemplateVersion(
        template_id=template.id,
        version=template.version,
        content=dict(template.content),
        change_summary=change_summary,
        is_important=False,
        parent_version_id=previous.id if previous is not None else None,
    )
    session.add(row)
    # 模板主表记录「当前版本基于哪个历史版本」
    template.parent_version_id = previous.id if previous is not None else None
    # 先落盘新行再淘汰，保证计数包含刚写入的版本
    await session.flush()
    await _apply_retention(session, template)
    return row


async def create_template(
    session: AsyncSession,
    payload: TemplateCreate,
    *,
    change_summary: str = CREATE_SUMMARY,
    learning_key: str | None = None,
) -> RuleTemplate:
    """创建模板：version=1、is_active=true，同时写入版本 1 快照行。"""
    await _ensure_category_exists(session, payload.category_id)

    template = RuleTemplate(
        type=payload.type,
        name=payload.name,
        category_id=payload.category_id,
        learning_key=learning_key,
        conditions=None,  # decomposition 模板 conditions 恒为 null（spec）
        content={
            "common_steps": list(payload.common_steps),
            "estimated_subtask_count": payload.estimated_subtask_count,
            "notes": payload.notes or "",
        },
        is_active=True,
        version=1,
    )
    session.add(template)
    # 先 flush 拿到模板 id，版本行的 template_id 外键才能落位
    await session.flush()
    await _append_version(session, template, change_summary=change_summary)

    template.updated_at = _now()
    await session.commit()
    await session.refresh(template)
    return template


async def update_template(
    session: AsyncSession,
    template_id: uuid.UUID,
    payload: TemplateUpdate,
    *,
    change_summary: str = MANUAL_EDIT_SUMMARY,
    expected_version: int | None = None,
) -> RuleTemplate:
    """编辑模板：content 实际变化才 version+1 并写快照；is_active 随时可切换。"""
    template = (
        await session.execute(
            select(RuleTemplate)
            .where(RuleTemplate.id == template_id)
            .with_for_update()
            .execution_options(populate_existing=True)
        )
    ).scalar_one_or_none()
    if template is None:
        raise NotFound(ResultCode.TEMPLATE_NOT_FOUND)
    if expected_version is not None and template.version != expected_version:
        raise Conflict("模板已被修改，请重新读取后再更新")
    data = payload.model_dump(exclude_unset=True)

    if data.get("name") is not None:
        name = data["name"].strip()
        if not name:
            raise BadRequest(ResultCode.BAD_REQUEST_VALIDATION, "模板名称不能为空")
        template.name = name

    # 在现有 content 上做增量合并，逐字段判断是否发生实际变化
    content = dict(template.content)
    content_changed = False
    if data.get("common_steps") is not None:
        new_steps = list(data["common_steps"])
        if new_steps != content.get("common_steps"):
            content_changed = True
        content["common_steps"] = new_steps
    if data.get("estimated_subtask_count") is not None:
        new_count = data["estimated_subtask_count"]
        if new_count != content.get("estimated_subtask_count"):
            content_changed = True
        content["estimated_subtask_count"] = new_count
    if data.get("notes") is not None:
        new_notes = data["notes"]
        if new_notes != content.get("notes", ""):
            content_changed = True
        content["notes"] = new_notes

    if data.get("is_active") is not None:
        template.is_active = data["is_active"]

    if content_changed:
        # 整份替换 content（新对象赋值，保证 JSONB 变更被 SQLAlchemy 感知）
        template.content = content
        template.version += 1
        if change_summary == MANUAL_EDIT_SUMMARY:
            template.last_manual_edit_at = _now()
        await session.flush()
        await _append_version(session, template, change_summary=change_summary)

    # 强制刷新 updated_at：即使字段值未变化也发出 UPDATE（与任务更新契约一致）
    template.updated_at = _now()
    await session.commit()
    await session.refresh(template)
    return template


async def list_versions(
    session: AsyncSession, template_id: uuid.UUID
) -> list[RuleTemplateVersion]:
    """版本历史：按 version 倒序（spec：最新版本在前）。"""
    await get_template_or_404(session, template_id)
    result = await session.execute(
        select(RuleTemplateVersion)
        .where(RuleTemplateVersion.template_id == template_id)
        .order_by(RuleTemplateVersion.version.desc())
    )
    return list(result.scalars().all())


async def set_version_important(
    session: AsyncSession,
    template_id: uuid.UUID,
    version_id: uuid.UUID,
    is_important: bool,
) -> RuleTemplateVersion:
    """切换版本重要标记：被标记的行在保留策略淘汰时永不被自动删除。"""
    await get_template_or_404(session, template_id)
    row = await session.get(RuleTemplateVersion, version_id)
    if row is None or row.template_id != template_id:
        raise NotFound(ResultCode.TEMPLATE_NOT_FOUND, "模板版本不存在")
    row.is_important = is_important
    await session.commit()
    await session.refresh(row)
    return row
