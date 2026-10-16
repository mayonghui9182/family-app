"""MCP 工具调用网关：封套解包与错误归一（拆解/总结链路共用）。

约定：
- 业务级失败（封套 code 非 0）抛 `McpToolError`，携带中文 msg；
- 传输级失败（连接失败/超时）由底层 `call_mcp_tool` 原样向上抛，
  由调用链路统一降级为 SSE error 事件（spec：MCP 不可达 → 拆解失败可重试）。
"""

from __future__ import annotations

import logging
from typing import Any

from src.config import settings
from src.mcp_client.client import call_mcp_tool

logger = logging.getLogger("agent_service.mcp_gateway")


class McpToolError(Exception):
    """MCP 工具业务级失败：封套 code 非 0。"""

    def __init__(self, code: int, msg: str) -> None:
        self.code = code
        self.msg = msg
        super().__init__(f"MCP 工具失败 code={code} msg={msg}")


async def call_tool(name: str, arguments: dict[str, Any] | None = None) -> Any:
    """调用 MCP 工具并解包统一封套，返回 data。

    task-service 所有工具都返回 `{"code","msg","data"}`；
    code 非 0 时 msg 为中文原因，转成 `McpToolError` 供链路分支处理。
    """
    result = await call_mcp_tool(
        settings.mcp_task_service_url,
        name,
        arguments,
        timeout_seconds=settings.mcp_timeout_seconds,
    )
    if result.isError:
        # 协议级错误（工具未注册等）：无封套可解，归一为业务错误
        raise McpToolError(-1, f"MCP 工具 {name} 协议级错误")
    envelope = result.structuredContent or {}
    if envelope.get("code") != 0:
        raise McpToolError(envelope.get("code", -1), envelope.get("msg", "MCP 调用失败"))
    return envelope.get("data")
