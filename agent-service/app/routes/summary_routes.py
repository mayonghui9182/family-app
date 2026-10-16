"""A-2 手动总结触发（Change 5）：OWNER/ADULT 才可；CHILD→40302；同日已跑非 force→46502。

契约：POST /api/v1/summary/trigger，头 X-Access-Token + X-Family-Id（沿用 A-1 惯例），
body {force: bool}；异步执行，立即返回 {trigger_id, estimated_finish_at, message}。
注：规格原文写 /api/agent/summary/trigger，本服务 A 组统一前缀 /api/v1（design.md D7 偏差记录），
      Change 6 前端经 backend 代理映射。
权限链路：AuthVerifier.verify（token 失效 40103 / 越权 46000 / CHILD 40302）→ C-19 抢锁
（未抢到 → 46502；force=true 由后端先删同日行再重插）→ asyncio.create_task 后台跑。
"""
import asyncio
import uuid
from collections.abc import Coroutine
from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import APIRouter, Depends, Request
from loguru import logger
from pydantic import BaseModel, Field

from ..models.errors import AuthError, ParamError, SummaryAlreadyRanError
from ..services.auth_verifier import AuthVerifier, CurrentUser
from ..services.summarizer import Summarizer

router = APIRouter(prefix="/api/v1", tags=["summary"])

_ESTIMATED_MINUTES = 15

_summarizer_singleton: Summarizer | None = None
_auth_singleton: AuthVerifier | None = None
_background_tasks: set[asyncio.Task[Any]] = set()


def get_summarizer() -> Summarizer:
    """进程级 Summarizer 单例（测试用 app.dependency_overrides 替换）。"""
    global _summarizer_singleton
    if _summarizer_singleton is None:
        _summarizer_singleton = Summarizer()
    return _summarizer_singleton


def get_auth_verifier() -> AuthVerifier:
    """进程级 AuthVerifier 单例（沿用 A-1 /api/auth/me 校验链路）。"""
    global _auth_singleton
    if _auth_singleton is None:
        _auth_singleton = AuthVerifier()
    return _auth_singleton


class SummaryTriggerRequest(BaseModel):
    """A-2 请求体。"""

    force: bool = Field(default=False, description="强制重跑（后端先清除同日锁记录再抢锁）")


class SummaryTriggerResponse(BaseModel):
    """A-2 响应：异步受理，立即返回跟踪号。"""

    trigger_id: str
    estimated_finish_at: str
    message: str


def _spawn(coro: Coroutine[Any, Any, Any]) -> None:
    """后台任务持有强引用防 GC，完成后自动出集合（可测）。"""
    task = asyncio.create_task(coro)
    _background_tasks.add(task)
    task.add_done_callback(_background_tasks.discard)


async def _run_background(summarizer: Summarizer, family_id: int, log_id: int, trigger_id: str) -> None:
    try:
        await summarizer.run_claimed(family_id, log_id)
        logger.info("A-2 异步总结完成 trigger={}", trigger_id)
    except Exception:  # noqa: BLE001 run_claimed 内部已收尾 failed
        logger.exception("A-2 异步总结失败 trigger={}", trigger_id)


@router.post("/summary/trigger", response_model=SummaryTriggerResponse, summary="A-2 手动总结触发")
async def trigger_summary(
    request: Request,
    body: SummaryTriggerRequest | None = None,
    summarizer: Summarizer = Depends(get_summarizer),
    auth: AuthVerifier = Depends(get_auth_verifier),
) -> SummaryTriggerResponse:
    x_access_token = request.headers.get("X-Access-Token", "")
    if not x_access_token:
        raise AuthError()
    raw_family = request.headers.get("X-Family-Id", "")
    try:
        family_id = int(raw_family)
    except ValueError as exc:
        raise ParamError("家庭ID格式不正确") from exc
    if family_id < 1:
        raise ParamError("家庭ID不合法")
    user: CurrentUser = await auth.verify(family_id, x_access_token)
    force = bool(body.force) if body is not None else False
    claim = await summarizer.claim(user.family_id, "manual", force)
    if not claim.get("acquired"):
        raise SummaryAlreadyRanError()
    trigger_id = uuid.uuid4().hex[:12]
    _spawn(_run_background(summarizer, user.family_id, int(claim["log_id"]), trigger_id))
    est = datetime.now(UTC) + timedelta(minutes=_ESTIMATED_MINUTES)
    return SummaryTriggerResponse(
        trigger_id=trigger_id,
        estimated_finish_at=est.isoformat(timespec="seconds"),
        message="总结已开始，稍后自动更新结果",
    )
