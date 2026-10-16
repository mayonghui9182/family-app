"""POST /api/chat：会话式任务拆解 SSE 入口（tasks.md 8.1，spec ai-decomposition）。

契约：
- `message` 缺失或空白 → 400，且不开流（响应不是 text/event-stream）
- 成功：按序发 `classification → reasoning×N → proposal → done`，data 均为 JSON
- 失败：只发一个 `error` 事件（文案「拆解失败，请重试」），其后不再有 proposal/done
- 整体 30 秒超时（可配置），超时同样降级为 `error`
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from src.config import settings
from src.llm.client import LLMClient
from src.services.decomposition import decompose

logger = logging.getLogger("agent_service.chat")

router = APIRouter()

# 面向用户的统一错误文案（spec 固化）
ERROR_MESSAGE = "拆解失败，请重试"


class ChatRequest(BaseModel):
    """/api/chat 请求体：message 必填非空（空白由业务层判 400，避免 422）。"""

    message: str = ""


def _sse(event: str, data: dict[str, Any]) -> str:
    """编码一帧 SSE：event 行 + JSON data 行 + 空行。"""
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


def _build_llm() -> LLMClient:
    """LLM 客户端工厂：独立成函数便于测试注入桩 transport。"""
    return LLMClient()


@router.post("/api/chat")
async def chat(body: ChatRequest) -> StreamingResponse:
    """SSE 拆解入口：先校验再开流。"""
    if not body.message or not body.message.strip():
        # 400 在开流之前抛出：响应为 JSON，不是 text/event-stream
        raise HTTPException(status_code=400, detail="message 不能为空")

    async def stream():
        # 边产出边发送：classification/reasoning 即时可见；
        # 中途失败时已发事件保留（spec 允许），仅保证 error 后无 proposal/done
        try:
            llm = _build_llm()
            async with asyncio.timeout(
                settings.decomposition_total_timeout_seconds
            ):
                async for name, data in decompose(body.message, llm):
                    yield _sse(name, data)
        except Exception as exc:  # LLM/MCP/超时/业务失败统一收敛为单个 error 事件
            logger.warning("拆解失败：%s", exc, extra={"error": str(exc)})
            yield _sse("error", {"message": ERROR_MESSAGE})

    return StreamingResponse(
        stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache"},
    )
