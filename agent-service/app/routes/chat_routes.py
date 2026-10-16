"""A 组用户端路由：A-1 SSE 流式拆解 + A-2 缓冲式（同编排器不同出口）。"""
from collections.abc import AsyncIterator
from typing import Any

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse

from ..models.api_schemas import ChatStreamRequest
from ..models.errors import AuthError, ParamError
from ..services.decomposer import Decomposer
from ..settings import get_settings

router = APIRouter(prefix="/api/v1", tags=["chat"])

_singleton: Decomposer | None = None


def get_decomposer() -> Decomposer:
    """进程级单例；测试用 app.dependency_overrides 替换。"""
    global _singleton
    if _singleton is None:
        _singleton = Decomposer()
    return _singleton


def _family_id_from(request: Request) -> int:
    """X-Family-Id 必填、int、ge=1；违规 → 40099。"""
    raw = request.headers.get("X-Family-Id")
    if not raw:
        raise ParamError("请在请求头中指定家庭ID")
    try:
        fid = int(raw)
    except ValueError as exc:
        raise ParamError("家庭ID格式不正确") from exc
    if fid < 1:
        raise ParamError("家庭ID不合法")
    return fid


def _access_token_from(request: Request) -> str:
    token = request.headers.get("X-Access-Token", "")
    if not token:
        raise AuthError()
    return token


def _sse_headers() -> dict[str, str]:
    return {
        "Cache-Control": "no-cache",
        "Connection": "keep-alive",
        "X-Accel-Buffering": "no",
    }


@router.post("/chat/stream")
async def chat_stream(
    request: Request,
    body: ChatStreamRequest,
    decomposer: Decomposer = Depends(get_decomposer),
) -> StreamingResponse:
    """A-1：SSE 五事件流（classification → reasoning×N → proposal → done / error）。"""
    family_id = _family_id_from(request)
    token = _access_token_from(request)
    rm = request.headers.get("X-Prefers-Reduced-Motion", "").strip().lower() == "true"

    async def gen() -> AsyncIterator[str]:
        async for frame in decomposer.stream(
            family_id=family_id,
            title=body.message,
            access_token=token,
            reduced_motion=rm,
            session_id=body.session_id,
            session_type=body.session_type,
        ):
            yield frame

    return StreamingResponse(gen(), media_type="text/event-stream", headers=_sse_headers())


@router.post("/chat")
async def chat_buffered(
    request: Request,
    body: ChatStreamRequest,
    decomposer: Decomposer = Depends(get_decomposer),
) -> dict[str, Any]:
    """A-2：缓冲式拆解，复用同一编排器，一次性返回最终结果 JSON。

    session_id 必须透传：传了就走 A-1 交互式（proposal 存 session，不写业务表），
    漏传会被编排器判成 B-1 直接建任务，微信草稿箱的「确认前零写入」当场失效。
    """
    family_id = _family_id_from(request)
    token = _access_token_from(request)
    sink: dict[str, Any] = {}
    async for _frame in decomposer.stream(
        family_id=family_id,
        title=body.message,
        access_token=token,
        session_id=body.session_id,
        result_sink=sink,
    ):
        pass
    if "task_id" not in sink:
        return {"code": int(sink.get("error_code", 50000)), "msg": str(sink.get("error_msg", "服务器开小差了"))}
    return {
        "code": 0,
        "msg": "操作成功",
        "data": {
            "task_id": sink["task_id"],
            "title": sink.get("title"),
            "subtasks": sink.get("subtasks", []),
        },
    }


# 引用避免未使用告警：get_settings 留给后续限流/超时动态化
_ = get_settings
