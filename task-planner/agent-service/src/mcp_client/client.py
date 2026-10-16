"""MCP Client：连接 task-service `/mcp` 的 Streamable HTTP 客户端（tasks.md 6.4）。

agent-service 不直连数据库，所有读写都经工具调用完成；
每次调用记录工具名与耗时（结构化日志，供排障与耗时统计）。
"""

from __future__ import annotations

import logging
import time
from typing import Any

from mcp.client.session import ClientSession
from mcp.client.streamable_http import streamablehttp_client
from src.work_auth import current_identity

logger = logging.getLogger("agent_service.mcp_client")


async def call_mcp_tool(
    url: str,
    name: str,
    arguments: dict[str, Any] | None = None,
    *,
    timeout_seconds: float = 10.0,
) -> Any:
    """调用单个 MCP 工具，返回 CallToolResult。

    业务结果在 `result.structuredContent` 的统一封套里（code 非 0 为失败）；
    每次调用新开连接：task-service 为 stateless 模式，无会话状态可复用，
    短连接更简单可靠。日志记录工具名、耗时与是否协议级报错。
    """
    started = time.perf_counter()
    identity = current_identity.get()
    if identity is None:
        raise PermissionError("MCP 调用缺少已认证用户")
    async with streamablehttp_client(url, timeout=timeout_seconds, headers=identity.headers) as (
        read_stream,
        write_stream,
        _,
    ):
        async with ClientSession(read_stream, write_stream) as session:
            await session.initialize()
            result = await session.call_tool(name, arguments or {})
    elapsed_ms = (time.perf_counter() - started) * 1000
    logger.info(
        "MCP 工具调用 tool=%s elapsed_ms=%.1f is_error=%s",
        name,
        elapsed_ms,
        result.isError,
        # 结构化字段：JSON 行日志中独立成键，便于检索与统计
        extra={"tool": name, "elapsed_ms": round(elapsed_ms, 1), "is_error": result.isError},
    )
    return result
