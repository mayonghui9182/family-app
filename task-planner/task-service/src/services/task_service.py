"""任务业务逻辑：查询/创建/更新/删除（REST 与 MCP 工具共用）。

关键契约（spec: task-mgmt）：
- 列表：status/category/source/keyword/due_before 可组合过滤，created_at 倒序，
  过期 draft 一律隐藏（谓词 status != 'draft' OR expires_at > now()）；
- 详情：聚合子待办（sort_order,id 升序）+ 任务级 notes + 子待办级 notes；
- 创建：title 必填非空；未知 category_id 拒绝（400）；draft 携带 24h 过期；
- 更新：5 值状态机白名单校验；draft→pending 清空 expires_at；updated_at 必刷新；
- 删除：物理删除，级联清理子待办与两级 notes（notes 无外键，应用层补删）。
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy import delete, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from src.models.category import Category
from src.models.enums import is_valid_transition
from src.models.note import Note
from src.models.task import Task
from src.schemas.task import (
    TaskCreate,
    TaskDetailOut,
    TaskUpdate,
    task_to_detail_out,
)
from src.services.envelope import (
    BadRequest,
    InvalidStatusTransition,
    NotFound,
    ResultCode,
)

# draft 提案有效期：创建时间 + 24 小时（spec 容差 1 分钟）
DRAFT_TTL = timedelta(hours=24)


def _now() -> datetime:
    """统一取当前 UTC 时间（与数据库 timezone-aware 列对齐）。"""
    return datetime.now(UTC)


def _draft_visibility_filter(now: datetime):
    """draft 可见性谓词：非 draft 恒可见；draft 仅在未过期时可见。"""
    return or_(Task.status != "draft", Task.expires_at > now)


async def get_task_or_404(session: AsyncSession, task_id: uuid.UUID) -> Task:
    """按主键取任务，不存在抛 404（TASK_NOT_FOUND）。"""
    task = await session.get(Task, task_id)
    if task is None:
        raise NotFound(ResultCode.TASK_NOT_FOUND)
    return task


def ensure_visible(task: Task) -> None:
    """过期提案不可读取或确认，清理仍可直接读取底层记录。"""
    if task.status == "draft" and (
        task.expires_at is None or task.expires_at <= _now()
    ):
        raise NotFound(ResultCode.TASK_NOT_FOUND, "提案已过期")


async def _ensure_category_exists(
    session: AsyncSession, category_id: uuid.UUID | None
) -> None:
    """校验分类存在性：未知 category_id 按 spec 以 400 拒绝。"""
    if category_id is None:
        return
    category = await session.get(Category, category_id)
    if category is None:
        raise BadRequest(ResultCode.BAD_REQUEST_VALIDATION, "分类不存在")


async def list_tasks(
    session: AsyncSession,
    *,
    status: str | None = None,
    category_id: uuid.UUID | None = None,
    source: str | None = None,
    keyword: str | None = None,
    due_before: datetime | None = None,
) -> list[Task]:
    """组合过滤查询任务列表，created_at 倒序，过期 draft 隐藏。"""
    stmt = select(Task).where(_draft_visibility_filter(_now()))
    if status is not None:
        stmt = stmt.where(Task.status == status)
    if category_id is not None:
        stmt = stmt.where(Task.category_id == category_id)
    if source is not None:
        stmt = stmt.where(Task.source == source)
    if keyword:
        # keyword 同时匹配标题与描述（ilike 不区分大小写，兼容中英文）
        pattern = f"%{keyword}%"
        stmt = stmt.where(
            or_(Task.title.ilike(pattern), Task.description.ilike(pattern))
        )
    if due_before is not None:
        stmt = stmt.where(Task.due_date < due_before)
    stmt = stmt.order_by(Task.created_at.desc(), Task.id)
    result = await session.execute(stmt)
    return list(result.scalars().all())


async def get_task_detail(session: AsyncSession, task_id: uuid.UUID) -> TaskDetailOut:
    """任务详情聚合：任务字段 + 子待办（各带 notes）+ 任务级 notes。"""
    task = await get_task_or_404(session, task_id)
    ensure_visible(task)

    # 任务级 notes：按创建时间升序，稳定输出
    task_notes = list(
        (
            await session.execute(
                select(Note)
                .where(Note.target_type == "task", Note.target_id == task.id)
                .order_by(Note.created_at, Note.id)
            )
        )
        .scalars()
        .all()
    )

    # 子待办级 notes：一次 IN 查询后按 target_id 分组，避免 N+1
    subtask_notes: dict[uuid.UUID, list[Note]] = {}
    subtask_ids = [st.id for st in task.subtasks]
    if subtask_ids:
        rows = (
            (
                await session.execute(
                    select(Note)
                    .where(
                        Note.target_type == "subtask", Note.target_id.in_(subtask_ids)
                    )
                    .order_by(Note.created_at, Note.id)
                )
            )
            .scalars()
            .all()
        )
        for note in rows:
            subtask_notes.setdefault(note.target_id, []).append(note)

    return task_to_detail_out(task, task_notes, subtask_notes)


async def create_task(session: AsyncSession, payload: TaskCreate) -> Task:
    """创建任务：默认 pending/medium/manual；draft 携带 24h 过期时间。"""
    await _ensure_category_exists(session, payload.category_id)

    task = Task(
        title=payload.title,
        description=payload.description,
        category_id=payload.category_id,
        status=payload.status,
        priority=payload.priority,
        due_date=payload.due_date,
        source="manual",  # REST 手动创建恒为 manual（agent 走 MCP 时另行赋值）
    )
    if payload.status == "draft":
        # draft 提案 24h 过期；其他状态 expires_at 必须为 null
        task.expires_at = _now() + DRAFT_TTL

    session.add(task)
    await session.commit()
    await session.refresh(task)
    return task


async def update_task(
    session: AsyncSession, task_id: uuid.UUID, payload: TaskUpdate
) -> Task:
    """部分更新任务：状态机白名单校验 + draft 确认清空过期 + updated_at 刷新。"""
    task = await get_task_or_404(session, task_id)
    ensure_visible(task)
    data = payload.model_dump(exclude_unset=True)

    if "title" in data:
        title = (data["title"] or "").strip()
        if not title:
            raise BadRequest(ResultCode.BAD_REQUEST_TITLE_EMPTY)
        task.title = title
    if "description" in data:
        task.description = data["description"]
    if "priority" in data and data["priority"] is not None:
        task.priority = data["priority"]
    if "due_date" in data:
        task.due_date = data["due_date"]
    if "category_id" in data:
        await _ensure_category_exists(session, data["category_id"])
        task.category_id = data["category_id"]

    if "status" in data and data["status"] is not None:
        target = data["status"]
        if target != task.status:
            # 5 值状态机：白名单外一律拒绝（含终态复活、跨级跳转）
            if not is_valid_transition(task.status, target):
                raise InvalidStatusTransition(
                    f"任务状态不能从 {task.status} 切换到 {target}"
                )
            task.status = target
            if target != "draft":
                # draft→pending 确认：清空过期时间；其他状态恒无过期
                task.expires_at = None
        # 目标状态与当前相同：视为幂等 no-op，不做流转校验

    # 强制刷新 updated_at：即使字段值未变化也发出 UPDATE（spec 契约）
    task.updated_at = _now()
    await session.commit()
    await session.refresh(task)
    return task


async def list_tasks_changed_since(
    session: AsyncSession,
    since: datetime,
    *,
    until: datetime | None = None,
    offset: int = 0,
    limit: int = 200,
) -> list[Task]:
    """时间窗口查询：`since` 之后新建或发生变更的任务（updated_at 倒序）。

    每日总结据此圈定「当天 0:00 至执行时刻」的数据范围：子待办的增删改会刷新父任务
    `updated_at`（见 subtask_service._touch_parent），因此任务级与子待办级修改都能被
    这一对谓词捕获；过期未确认的 draft 与列表接口口径一致地隐藏。
    """
    stmt = (
        select(Task)
        .where(
            or_(Task.created_at >= since, Task.updated_at >= since),
            _draft_visibility_filter(_now()),
        )
        .order_by(Task.updated_at.desc(), Task.id)
    )
    if until is not None:
        stmt = stmt.where(Task.updated_at <= until)
    result = await session.execute(stmt.offset(offset).limit(limit))
    return list(result.scalars().all())


async def cancel_expired_drafts(
    session: AsyncSession, now: datetime | None = None
) -> list[Task]:
    """批量把 `expires_at` 已过期的 draft 置为 cancelled（每日总结顺带清理）。

    draft→cancelled 属状态机白名单内的合法流转；同时清空 `expires_at` 以维持
    「非 draft 状态无过期时间」的不变式。未过期的 draft 与其他状态一律不动，
    返回本次被取消的任务列表。
    """
    moment = now or _now()
    stmt = (
        select(Task)
        .where(
            Task.status == "draft",
            Task.expires_at.is_not(None),
            Task.expires_at <= moment,
        )
        .order_by(Task.created_at, Task.id)
    )
    rows = list((await session.execute(stmt)).scalars().all())
    for task in rows:
        task.status = "cancelled"
        task.expires_at = None
        task.updated_at = moment
    if rows:
        await session.commit()
    return rows


async def delete_task(session: AsyncSession, task_id: uuid.UUID) -> None:
    """物理删除任务：级联删除子待办，并补删任务级与子待办级 notes。"""
    task = await get_task_or_404(session, task_id)
    subtask_ids = [st.id for st in task.subtasks]

    # notes 无数据库外键，必须应用层补删（先子待办级，再任务级）
    if subtask_ids:
        await session.execute(
            delete(Note).where(
                Note.target_type == "subtask", Note.target_id.in_(subtask_ids)
            )
        )
    await session.execute(
        delete(Note).where(Note.target_type == "task", Note.target_id == task.id)
    )

    # 子待办由 ORM delete-orphan + 数据库 ON DELETE CASCADE 双保险清理
    await session.delete(task)
    await session.commit()
