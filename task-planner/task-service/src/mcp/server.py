"""MCP Server：agent-service 访问数据的唯一通道（tasks.md 6.1-6.3，design.md D3）。

设计要点：
- 21 个工具全部薄封装 `src.services` 业务层，REST 与 MCP 共用同一套规则，
  不出现第二份状态机/唯一性/版本策略实现；
- Streamable HTTP + `stateless_http=True`：无会话状态，工具内自建数据库会话
  （`session_factory`，测试可注入 NullPool 版本以隔离事件循环）；
- 错误不抛协议异常，而是返回统一封套 `{"code","msg","data"}`：code 非 0 时
  msg 为中文原因，LLM 可据此自行纠错重试（对老年用户也保持中文可读）；
- 挂载为精确的 `POST /mcp`：把 SDK 生成的 Route 按目标路径追加到 FastAPI router，
  而非 `app.mount()`（SDK 的 Route 用绝对路径，mount 会叠加成 `/mcp/mcp`）。
"""

from __future__ import annotations

import functools
import logging
import uuid
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import FastAPI
from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from pydantic import ValidationError
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession
from starlette.routing import Route

from src.config import settings
from src.ownership import owner_id, ensure_category
from src.work_auth import AuthenticationError, verify_identity
from src.db import SessionLocal
from src.models.category import Category
from src.models.enums import (
    NOTE_TARGET_TYPES,
    PRIORITIES,
    SUMMARY_STATUSES,
    SUMMARY_TRIGGER_SOURCES,
    TASK_SOURCES,
    TASK_STATUSES,
    TEMPLATE_TYPES,
)
from src.models.note import Note
from src.models.rule_template import RuleTemplateVersion
from src.models.subtask import Subtask
from src.models.summary_log import SummaryExecutionLog
from src.models.task import Task
from src.schemas.category import CategoryCreate, CategoryUpdate, category_to_out
from src.schemas.note import note_to_out
from src.schemas.subtask import SubtaskCreate, SubtaskUpdate
from src.schemas.task import TaskCreate, TaskUpdate, subtask_to_out, task_to_out
from src.schemas.template import (
    TemplateCreate,
    TemplateUpdate,
    template_to_out,
    template_version_to_out,
)
from src.services import (
    category_service,
    note_service,
    subtask_service,
    task_service,
    template_service,
)
from src.services.envelope import (
    ApiError,
    BadRequest,
    Conflict,
    NotFound,
    ResultCode,
    fail,
    ok,
)

logger = logging.getLogger("task_service.mcp")

# 未完成状态的补集：workload 统计口径（draft 提案也算「还没做完的事」）
_FINISHED_STATUSES = ("completed", "cancelled")
# content 允许的三字段（design.md D8：decomposition 模板整份 JSONB）
_CONTENT_FIELDS = ("common_steps", "estimated_subtask_count", "notes")

mcp_server = FastMCP(
    "task-service",
    instructions=(
        "工作任务计划系统的唯一数据通道：任务/子待办/注意事项/分类的读写，"
        "以及拆解模板与每日总结执行日志。所有工具返回统一封套 "
        '{"code":0,"msg":"success","data":...}；code 非 0 表示失败，msg 为中文原因。'
    ),
    streamable_http_path="/mcp",
    stateless_http=True,
    # 显式给出传输安全策略：SDK 在 host 为回环默认值时会自动开启 DNS rebinding
    # 防护且只放行 localhost 类 Host 头，compose 内 agent-service 以服务名访问
    # （Host: task-service:8100）会被拒 421。这里保留防护，白名单走配置。
    transport_security=TransportSecuritySettings(
        enable_dns_rebinding_protection=True,
        allowed_hosts=settings.mcp_allowed_host_list,
        # Origin 缺失（服务间调用）本就放行；带上同源前缀便于浏览器直连调试
        allowed_origins=[f"http://{host}" for host in settings.mcp_allowed_host_list],
    ),
)

#: 工具使用的会话工厂；生产为 `src.db.SessionLocal`，测试注入 NullPool 版本
session_factory: Callable[[], AsyncSession] = SessionLocal


@asynccontextmanager
async def _session() -> AsyncIterator[AsyncSession]:
    """工具级数据库会话：stateless HTTP 下没有请求上下文，工具自建会话。"""
    async with session_factory() as session:
        await ensure_category(session)
        yield session


def _validation_message(exc: ValidationError) -> str:
    """把 pydantic 校验错误压成一句中文提示，点名出错字段（供 LLM 自纠）。"""
    parts = []
    for err in exc.errors():
        loc = ".".join(str(item) for item in err.get("loc", ()) if item)
        parts.append(f"{loc} {err.get('msg', '')}".strip())
    if not parts:
        return ResultCode.BAD_REQUEST_VALIDATION.msg
    return "参数校验失败：" + "；".join(parts)


def _guarded(func: Callable[..., Any]) -> Callable[..., Any]:
    """工具统一异常转换：领域异常/校验异常 → 封套返回值（不触发协议级 isError）。"""

    @functools.wraps(func)
    async def wrapper(*args: Any, **kwargs: Any) -> dict[str, Any]:
        scope_token = None
        try:
            if owner_id.get() is None:
                request = mcp_server.get_context().request_context.request
                if request is None:
                    raise AuthenticationError("缺少请求身份")
                identity = await verify_identity(request.headers,
                    backend_url=settings.backend_base_url,
                    internal_secret=settings.internal_api_secret)
                scope_token = owner_id.set(identity.user_id)
            return await func(*args, **kwargs)
        except (AuthenticationError, PermissionError):
            return {"code": 40101, "msg": "缺少有效工作身份", "data": None}
        except ApiError as exc:
            # 业务异常：沿用 REST 的业务码与中文文案，agent 可据 code 分支处理
            return fail(exc.code, exc.msg, exc.data)
        except ValidationError as exc:
            return fail(
                ResultCode.BAD_REQUEST_VALIDATION,
                _validation_message(exc),
                None,
            )
        except Exception:  # noqa: BLE001 - 兜底：细节只进日志，不外泄堆栈
            logger.exception("MCP 工具 %s 执行失败", func.__name__)
            return fail(ResultCode.INTERNAL_ERROR)
        finally:
            if scope_token is not None:
                owner_id.reset(scope_token)

    return wrapper


def _compact(**values: Any) -> dict[str, Any]:
    """丢弃 None 字段：MCP 工具无法区分「未传」与「显式 null」，一律视为不修改。"""
    return {key: value for key, value in values.items() if value is not None}


def _clamp(value: int, low: int, high: int) -> int:
    """把 limit 类参数夹到安全区间，避免 agent 传入 0 或超大值。"""
    return max(low, min(int(value), high))


def _enum_or_400(value: str, allowed: tuple[str, ...], field: str) -> str:
    """枚举取值校验：越界一律 400，中文提示可选值。"""
    if value not in allowed:
        raise BadRequest(
            ResultCode.BAD_REQUEST_VALIDATION,
            f"{field} 必须是 {'/'.join(allowed)} 之一",
        )
    return value


async def _resolve_category(
    session: AsyncSession,
    *,
    category_id: uuid.UUID | None = None,
    category_name: str | None = None,
) -> Category:
    """按 id 或名称解析分类：两者都缺 → 400，查不到 → 404（CATEGORY_NOT_FOUND）。"""
    if category_id is not None:
        return await category_service.get_category_or_404(session, category_id)
    if category_name and category_name.strip():
        name = category_name.strip()
        row = (
            (await session.execute(select(Category).where(Category.name == name)))
            .scalars()
            .first()
        )
        if row is None:
            raise NotFound(ResultCode.CATEGORY_NOT_FOUND, f"分类「{name}」不存在")
        return row
    raise BadRequest(
        ResultCode.BAD_REQUEST_VALIDATION, "必须提供 category_id 或 category_name 之一"
    )


# --------------------------------------------------------------------------
# 6.1 任务域工具（9 个）
# --------------------------------------------------------------------------


@mcp_server.tool()
@_guarded
async def get_tasks(
    status: str | None = None,
    category_id: uuid.UUID | None = None,
    source: str | None = None,
    keyword: str | None = None,
    due_before: datetime | None = None,
    limit: int = 50,
) -> dict[str, Any]:
    """查询任务列表，支持状态/分类/来源/关键词/截止时间组合过滤。

    返回按创建时间倒序的任务数组（含分类名）；已过期未确认的 draft 提案不返回。
    """
    if status is not None:
        _enum_or_400(status, TASK_STATUSES, "status")
    if source is not None:
        _enum_or_400(source, TASK_SOURCES, "source")
    size = _clamp(limit, 1, 200)
    async with _session() as session:
        rows = await task_service.list_tasks(
            session,
            status=status,
            category_id=category_id,
            source=source,
            keyword=keyword,
            due_before=due_before,
        )
        return ok([task_to_out(task).model_dump(mode="json") for task in rows[:size]])


@mcp_server.tool()
@_guarded
async def get_tasks_changed_since(
    since: datetime,
    limit: int = 200,
    until: datetime | None = None,
    offset: int = 0,
) -> dict[str, Any]:
    """查询 `since` 之后新建或发生变更的任务（每日总结的窗口查询）。

    谓词为 `created_at >= since` 或 `updated_at >= since`；子待办的增删改会刷新父任务
    `updated_at`，因此子待办级修改同样能被捕获。返回按 `updated_at` 倒序（含分类名），
    过期未确认的 draft 不返回。
    """
    size = _clamp(limit, 1, 200)
    async with _session() as session:
        rows = await task_service.list_tasks_changed_since(
            session, since, until=until, offset=max(0, offset), limit=size
        )
        return ok([task_to_out(task).model_dump(mode="json") for task in rows[:size]])


@mcp_server.tool()
@_guarded
async def get_task_detail(task_id: uuid.UUID) -> dict[str, Any]:
    """查询任务详情：含子待办（按 sort_order 升序，各带注意事项）与任务级注意事项。"""
    async with _session() as session:
        detail = await task_service.get_task_detail(session, task_id)
        return ok(detail.model_dump(mode="json"))


@mcp_server.tool()
@_guarded
async def create_task(
    title: str,
    description: str | None = None,
    category_id: uuid.UUID | None = None,
    status: str = "pending",
    priority: str = "medium",
    due_date: datetime | None = None,
    source: str = "agent",
) -> dict[str, Any]:
    """创建任务。

    AI 拆解提案用 `status="draft"` + `source="agent"`，服务会自动写入 24 小时后的
    `expires_at`；用户确认后由 `update_task` 流转到 pending（同时清空 expires_at）。
    """
    _enum_or_400(status, TASK_STATUSES, "status")
    _enum_or_400(priority, PRIORITIES, "priority")
    _enum_or_400(source, TASK_SOURCES, "source")
    payload = TaskCreate(
        title=title,
        description=description,
        category_id=category_id,
        status=status,
        priority=priority,
        due_date=due_date,
    )
    async with _session() as session:
        task = await task_service.create_task(session, payload)
        # 先序列化再改写 source：避免 commit 后触发关联懒加载
        data = task_to_out(task).model_dump(mode="json")
        if source != "manual":
            # service 层为 REST 场景硬编码 source="manual"，MCP 侧按调用方来源改写
            await session.execute(
                update(Task).where(Task.id == task.id).values(source=source)
            )
            await session.commit()
            data["source"] = source
        return ok(data)


@mcp_server.tool()
@_guarded
async def create_subtasks(
    task_id: uuid.UUID,
    subtasks: list[dict[str, Any]],
    template_id: uuid.UUID | None = None,
    template_version: int | None = None,
) -> dict[str, Any]:
    """为任务批量新增子待办，全部标记 `is_ai_generated=true`（对比学习的关键标记）。

    `subtasks` 每项形如 `{"title": "...", "description": "..."}`，至少 1 条；
    未给 `sort_order` 时按传入顺序从 1 开始连续编号。父任务不存在返回 40401。
    """
    if not subtasks:
        raise BadRequest(ResultCode.SUBTASK_BATCH_EMPTY)

    # 先整体校验再落库：避免第 N 条非法时前 N-1 条已提交造成半成品
    payloads: list[SubtaskCreate] = []
    for index, item in enumerate(subtasks, start=1):
        if not isinstance(item, dict):
            raise BadRequest(
                ResultCode.BAD_REQUEST_VALIDATION, f"第 {index} 条子待办必须是对象"
            )
        raw = dict(item)
        raw.setdefault("sort_order", index)
        payloads.append(SubtaskCreate(**raw))

    async with _session() as session:
        task = (
            await session.execute(
                select(Task).where(Task.id == task_id).with_for_update()
            )
        ).scalar_one_or_none()
        if task is None:
            raise NotFound(ResultCode.TASK_NOT_FOUND)
        task_service.ensure_visible(task)
        if task.ai_snapshot is not None:
            raise Conflict("原始提案已经保存，不可再次生成覆盖")
        if template_id is not None:
            template = await template_service.get_template_or_404(session, template_id)
            if (
                template.category_id != task.category_id
                or template.type != "decomposition"
            ):
                raise BadRequest("模板与任务分类不匹配")
            version = (
                template_version if template_version is not None else template.version
            )
            known = await session.scalar(
                select(RuleTemplateVersion.id).where(
                    RuleTemplateVersion.template_id == template_id,
                    RuleTemplateVersion.version == version,
                )
            )
            if known is None:
                raise Conflict("参考的模板版本已不存在，请重新拆解")
            template_version = version
        elif template_version is not None:
            raise BadRequest("模板版本必须同时提供模板 ID")

        created = []
        snapshot = []
        for payload, raw in zip(payloads, subtasks):
            row = Subtask(
                id=uuid.uuid4(),
                parent_task_id=task_id,
                **payload.model_dump(),
                is_ai_generated=True,
            )
            session.add(row)
            note = str(raw.get("notes") or "").strip()
            if note:
                session.add(Note(target_type="subtask", target_id=row.id, content=note))
            snapshot.append(
                {
                    "id": str(row.id),
                    "title": row.title,
                    "description": row.description,
                    "sort_order": row.sort_order,
                    "notes": note,
                }
            )
            created.append(row)
        task.ai_snapshot = {
            "template_id": str(template_id) if template_id else None,
            "template_version": template_version,
            "category_id": str(task.category_id) if task.category_id else None,
            "subtasks": snapshot,
        }
        task.updated_at = datetime.now(UTC)
        # 子待办、注意事项与原始快照在同一事务提交，失败时全部回滚。
        await session.commit()
        return ok([subtask_to_out(row).model_dump(mode="json") for row in created])


@mcp_server.tool()
@_guarded
async def update_task(
    task_id: uuid.UUID,
    title: str | None = None,
    description: str | None = None,
    category_id: uuid.UUID | None = None,
    status: str | None = None,
    priority: str | None = None,
    due_date: datetime | None = None,
) -> dict[str, Any]:
    """部分更新任务：不传（null）的字段保持不变。

    状态流转受 5 值状态机白名单约束（终态不可复活，非法流转返回 40014）；
    `draft→pending` 视为用户确认提案，会清空 `expires_at`。
    """
    if status is not None:
        _enum_or_400(status, TASK_STATUSES, "status")
    if priority is not None:
        _enum_or_400(priority, PRIORITIES, "priority")
    payload = TaskUpdate(
        **_compact(
            title=title,
            description=description,
            category_id=category_id,
            status=status,
            priority=priority,
            due_date=due_date,
        )
    )
    async with _session() as session:
        task = await task_service.update_task(session, task_id, payload)
        return ok(task_to_out(task).model_dump(mode="json"))


@mcp_server.tool()
@_guarded
async def update_subtask(
    subtask_id: uuid.UUID,
    title: str | None = None,
    description: str | None = None,
    sort_order: int | None = None,
    status: str | None = None,
) -> dict[str, Any]:
    """部分更新子待办：不传（null）的字段保持不变；状态机与任务共用同一白名单。"""
    if status is not None:
        _enum_or_400(status, TASK_STATUSES, "status")
    payload = SubtaskUpdate(
        **_compact(
            title=title,
            description=description,
            sort_order=sort_order,
            status=status,
        )
    )
    async with _session() as session:
        row = await subtask_service.update_subtask(session, subtask_id, payload)
        return ok(subtask_to_out(row).model_dump(mode="json"))


@mcp_server.tool()
@_guarded
async def delete_subtask(subtask_id: uuid.UUID) -> dict[str, Any]:
    """删除子待办（物理删除，并清理挂在其名下的注意事项）。"""
    async with _session() as session:
        await subtask_service.delete_subtask(session, subtask_id)
        return ok({"id": str(subtask_id), "deleted": True})


@mcp_server.tool()
@_guarded
async def add_note(
    target_type: str, target_id: uuid.UUID, content: str
) -> dict[str, Any]:
    """为任务或子待办追加注意事项。

    `target_type` ∈ {task, subtask}；`content` 去空白后不能为空（否则 40024）。
    """
    _enum_or_400(target_type, NOTE_TARGET_TYPES, "target_type")
    async with _session() as session:
        note = await note_service.create_note(
            session, target_type=target_type, target_id=target_id, content=content
        )
        return ok(note_to_out(note).model_dump(mode="json"))


# --------------------------------------------------------------------------
# 6.2 分类与上下文工具（5 个）
# --------------------------------------------------------------------------


@mcp_server.tool()
@_guarded
async def get_categories() -> dict[str, Any]:
    """列出全部分类（创建时间升序）。

    「未分类」是系统保留分类：AI 归类无匹配时归入它，且不得被合并或删除。
    """
    async with _session() as session:
        rows = await category_service.list_categories(session)
        return ok([category_to_out(row).model_dump(mode="json") for row in rows])


@mcp_server.tool()
@_guarded
async def create_category(name: str, description: str | None = None) -> dict[str, Any]:
    """创建分类：名称全局唯一，重名返回 40021。"""
    payload = CategoryCreate(name=name, description=description)
    async with _session() as session:
        row = await category_service.create_category(session, payload)
        return ok(category_to_out(row).model_dump(mode="json"))


@mcp_server.tool()
@_guarded
async def update_category(
    category_id: uuid.UUID, name: str, description: str | None = None
) -> dict[str, Any]:
    """重命名分类（同样受唯一性约束）；未知 id 返回 40402。"""
    payload = CategoryUpdate(name=name, description=description)
    async with _session() as session:
        row = await category_service.update_category(session, category_id, payload)
        return ok(category_to_out(row).model_dump(mode="json"))


@mcp_server.tool()
@_guarded
async def get_completed_tasks_by_category(
    category_name: str | None = None,
    category_id: uuid.UUID | None = None,
    limit: int = 5,
) -> dict[str, Any]:
    """few-shot 上下文：返回某分类下已完成的任务及其子待办（更新时间倒序）。

    返回结构 `{"category": 分类名, "tasks": [{"title","description","subtasks":[...]}]}`，
    供 LLM 参考用户过往的拆解习惯；只含 `status=completed` 的任务。
    """
    size = _clamp(limit, 1, 20)
    async with _session() as session:
        category = await _resolve_category(
            session, category_id=category_id, category_name=category_name
        )
        rows = (
            (
                await session.execute(
                    select(Task)
                    .where(Task.category_id == category.id, Task.status == "completed")
                    .order_by(Task.updated_at.desc(), Task.id)
                    .limit(size)
                )
            )
            .scalars()
            .all()
        )
        tasks = [
            {
                "title": task.title,
                "description": task.description,
                "subtasks": [
                    {
                        "title": row.title,
                        "status": row.status,
                        "sort_order": row.sort_order,
                    }
                    for row in task.subtasks
                ],
            }
            for task in rows
        ]
        return ok({"category": category.name, "tasks": tasks})


@mcp_server.tool()
@_guarded
async def get_current_workload(due_soon_days: int = 7) -> dict[str, Any]:
    """当前工作负载：未完成任务数、优先级分布、未来 N 天内到期数。

    未完成口径为 `status` 不属于 completed/cancelled（含待确认的 draft）；
    agent 在高优先级未完成数偏多时应提醒用户「先处理手头的事」。
    """
    days = _clamp(due_soon_days, 1, 90)
    now = datetime.now(UTC)
    horizon = now + timedelta(days=days)
    async with _session() as session:
        rows = (
            await session.execute(
                select(Task.priority, Task.due_date).where(
                    Task.status.notin_(_FINISHED_STATUSES),
                    task_service._draft_visibility_filter(now),
                )
            )
        ).all()

    distribution = {priority: 0 for priority in PRIORITIES}
    due_soon = 0
    for priority, due_date in rows:
        distribution[priority] = distribution.get(priority, 0) + 1
        if due_date is not None and now <= due_date <= horizon:
            due_soon += 1
    return ok(
        {
            "unfinished_count": len(rows),
            "priority_distribution": distribution,
            "due_soon_count": due_soon,
        }
    )


# --------------------------------------------------------------------------
# 6.3 学习闭环与每日总结工具（7 个）
# --------------------------------------------------------------------------


@mcp_server.tool()
@_guarded
async def get_rule_templates(
    template_type: str | None = None,
    category_name: str | None = None,
    category_id: uuid.UUID | None = None,
    is_active: bool | None = None,
    limit: int = 20,
    offset: int = 0,
) -> dict[str, Any]:
    """查询规则模板（含 content 整份内容与当前版本号）。

    agent 选拆解模板时传 `template_type="decomposition"` + `is_active=true`；
    分类可用 `category_name` 或 `category_id` 指定其一。
    """
    if template_type is not None:
        _enum_or_400(template_type, TEMPLATE_TYPES, "template_type")
    size = _clamp(limit, 1, 100)
    async with _session() as session:
        resolved_id = category_id
        if resolved_id is None and category_name:
            resolved_id = (
                await _resolve_category(session, category_name=category_name)
            ).id
        rows = await template_service.list_templates(
            session,
            template_type=template_type,
            category_id=resolved_id,
            is_active=is_active,
            offset=offset,
            limit=size,
        )
        return ok([template_to_out(row).model_dump(mode="json") for row in rows[:size]])


@mcp_server.tool()
@_guarded
async def create_rule_template(
    template_type: str,
    name: str,
    category_id: uuid.UUID | None = None,
    common_steps: list[str] | None = None,
    estimated_subtask_count: int = 0,
    notes: str = "",
    change_summary: str = "创建模板",
    learning_key: str | None = None,
) -> dict[str, Any]:
    """创建规则模板（version=1、is_active=true，并写入版本 1 快照行）。

    `template_type` ∈ {decomposition, routine}；每日总结在某分类有修改证据却没有
    decomposition 模板时自动建模板，此时 `change_summary` 传「每日总结自动创建」。
    未知 `category_id` 返回 400；`change_summary` 去空白后不能为空。
    """
    _enum_or_400(template_type, TEMPLATE_TYPES, "template_type")
    summary = (change_summary or "").strip()
    if not summary:
        raise BadRequest(ResultCode.BAD_REQUEST_VALIDATION, "change_summary 不能为空")

    payload = TemplateCreate(
        type=template_type,
        name=name,
        category_id=category_id,
        common_steps=list(common_steps or []),
        estimated_subtask_count=estimated_subtask_count,
        notes=notes or "",
    )
    async with _session() as session:
        template = await template_service.create_template(
            session, payload, change_summary=summary, learning_key=learning_key
        )
        return ok(template_to_out(template).model_dump(mode="json"))


@mcp_server.tool()
@_guarded
async def get_template_versions(
    template_id: uuid.UUID, limit: int = 20
) -> dict[str, Any]:
    """查询模板版本历史（version 倒序，含 `change_summary` 与 `created_at`）。

    每日总结据此判定「模板当天是否被用户手动编辑过」：版本行
    `change_summary == "用户手动编辑"` 且 `created_at` 落在当天，即切换到
    只追加不覆盖的冲突策略。未知 `template_id` 返回 40404。
    """
    size = _clamp(limit, 1, 100)
    async with _session() as session:
        rows = await template_service.list_versions(session, template_id)
        return ok(
            [
                template_version_to_out(row).model_dump(mode="json")
                for row in rows[:size]
            ]
        )


@mcp_server.tool()
@_guarded
async def update_rule_template(
    template_id: uuid.UUID,
    content: dict[str, Any],
    change_summary: str,
    name: str | None = None,
    is_active: bool | None = None,
    expected_version: int | None = None,
) -> dict[str, Any]:
    """写回模板内容（学习闭环，设计文档缺口的补充工具）。

    `content` 只接受 `common_steps`(list[str]) / `estimated_subtask_count`(int) /
    `notes`(str) 三字段，至少提供一个；实际发生变化才 `version+1` 并写版本快照行，
    `change_summary` 必填且会记入该版本行（如「每日总结自动补充：新增步骤X，理由Y」）。
    """
    summary = (change_summary or "").strip()
    if not summary:
        raise BadRequest(ResultCode.BAD_REQUEST_VALIDATION, "change_summary 不能为空")
    if not isinstance(content, dict):
        raise BadRequest(ResultCode.BAD_REQUEST_VALIDATION, "content 必须是对象")
    unknown = sorted(set(content) - set(_CONTENT_FIELDS))
    if unknown:
        raise BadRequest(
            ResultCode.BAD_REQUEST_VALIDATION,
            f"content 含未知字段：{'、'.join(unknown)}",
        )
    changes = {key: content[key] for key in _CONTENT_FIELDS if key in content}
    if not changes:
        raise BadRequest(
            ResultCode.BAD_REQUEST_VALIDATION,
            "content 至少包含 common_steps / estimated_subtask_count / notes 之一",
        )

    payload = TemplateUpdate(**changes, **_compact(name=name, is_active=is_active))
    async with _session() as session:
        template = await template_service.update_template(
            session,
            template_id,
            payload,
            change_summary=summary,
            expected_version=expected_version,
        )
        return ok(template_to_out(template).model_dump(mode="json"))


def _summary_log_to_dict(row: SummaryExecutionLog) -> dict[str, Any]:
    """执行日志行 → 工具返回体（写入与查询共用同一形状，避免两侧字段漂移）。"""
    return {
        "id": str(row.id),
        "run_at": row.run_at.isoformat(),
        "status": row.status,
        "error_message": row.error_message,
        "categories_updated": row.categories_updated,
        "trigger_source": row.trigger_source,
    }


@mcp_server.tool()
@_guarded
async def log_summary_execution(
    status: str,
    categories_updated: list[str] | None = None,
    error_message: str | None = None,
    trigger_source: str = "auto",
    run_at: datetime | None = None,
) -> dict[str, Any]:
    """写一条每日总结执行记录（`summary_execution_log`）。

    `status` ∈ {success, failed}；`trigger_source` ∈ {auto（22:00 定时）, manual（手动）}；
    `categories_updated` 为本次更新了模板的分类名列表。同日重复触发判定依赖该表。
    """
    _enum_or_400(status, SUMMARY_STATUSES, "status")
    _enum_or_400(trigger_source, SUMMARY_TRIGGER_SOURCES, "trigger_source")
    names = [str(item).strip() for item in (categories_updated or [])]
    names = [item for item in names if item]

    async with _session() as session:
        row = SummaryExecutionLog(
            status=status,
            error_message=error_message,
            categories_updated=names,
            trigger_source=trigger_source,
        )
        if run_at is not None:
            row.run_at = run_at
        session.add(row)
        await session.commit()
        await session.refresh(row)
        return ok(_summary_log_to_dict(row))


@mcp_server.tool()
@_guarded
async def get_summary_executions(
    since: datetime | None = None,
    status: str | None = None,
    trigger_source: str | None = None,
    limit: int = 50,
    offset: int = 0,
) -> dict[str, Any]:
    """查询每日总结执行日志（`run_at` 倒序）。

    `since` 只返回该时刻之后的记录（判定「当天是否已成功跑过」传当天 0:00，
    手动触发未带 force 时据此返回 409）；`status` ∈ {success, failed}、
    `trigger_source` ∈ {auto, manual} 可分别过滤，用于退避重试与连续失败天数统计。
    """
    if status is not None:
        _enum_or_400(status, SUMMARY_STATUSES, "status")
    if trigger_source is not None:
        _enum_or_400(trigger_source, SUMMARY_TRIGGER_SOURCES, "trigger_source")
    size = _clamp(limit, 1, 200)

    async with _session() as session:
        stmt = select(SummaryExecutionLog)
        if since is not None:
            stmt = stmt.where(SummaryExecutionLog.run_at >= since)
        if status is not None:
            stmt = stmt.where(SummaryExecutionLog.status == status)
        if trigger_source is not None:
            stmt = stmt.where(SummaryExecutionLog.trigger_source == trigger_source)
        stmt = stmt.order_by(SummaryExecutionLog.run_at.desc(), SummaryExecutionLog.id)
        rows = (
            (await session.execute(stmt.offset(max(0, offset)).limit(size)))
            .scalars()
            .all()
        )
        return ok([_summary_log_to_dict(row) for row in rows[:size]])


@mcp_server.tool()
@_guarded
async def cancel_expired_drafts(now: datetime | None = None) -> dict[str, Any]:
    """把 `expires_at` 已过期的 draft 批量置为 cancelled（每日总结顺带清理）。

    draft→cancelled 是状态机白名单内的合法流转，同时清空 `expires_at`；未过期的
    draft 与其他状态一律不动。`now` 缺省取服务器当前时间（测试可注入桩时钟）。
    返回 `cancelled_count` 与被取消的 `task_ids`。
    """
    async with _session() as session:
        rows = await task_service.cancel_expired_drafts(session, now)
        return ok(
            {
                "cancelled_count": len(rows),
                "task_ids": [str(row.id) for row in rows],
            }
        )


# --------------------------------------------------------------------------
# 挂载
# --------------------------------------------------------------------------

# SDK 的 StreamableHTTPSessionManager.run() 每个实例只能调用一次，
# 用进程级标记保证多次创建应用（如测试反复进入 TestClient）时不抛 RuntimeError
_manager_started = False


def mount_mcp(app: FastAPI, *, path: str = "/mcp") -> None:
    """把 MCP 的 Streamable HTTP 端点以精确路径挂到 FastAPI 应用。

    做法：取 `streamable_http_app()` 生成的唯一 Route（SDK 用绝对路径），按目标
    路径重建后追加到 `app.router.routes`，因此最终端点就是 `POST /mcp`；
    session manager 随应用 lifespan 启动。
    """
    asgi_app = mcp_server.streamable_http_app()
    route = asgi_app.routes[0]
    if not isinstance(route, Route):
        raise TypeError("MCP SDK 返回了不支持的路由类型")
    app.router.routes.append(
        Route(path, endpoint=route.endpoint, methods=route.methods, name=route.name)
    )

    original_lifespan = app.router.lifespan_context

    @asynccontextmanager
    async def lifespan_with_mcp(app_: FastAPI) -> AsyncIterator[None]:
        global _manager_started  # noqa: PLW0603 - 进程级单次启动标记
        if _manager_started:
            # 已启动过：复用同一个 manager（测试场景下 HTTP 端点可能已随上一次
            # lifespan 关闭，但内存通道与 REST 不受影响）
            async with original_lifespan(app_):
                yield
            return
        _manager_started = True
        async with original_lifespan(app_), mcp_server.session_manager.run():
            yield

    app.router.lifespan_context = lifespan_with_mcp


__all__ = ["mcp_server", "mount_mcp", "session_factory"]
