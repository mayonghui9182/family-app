"""MCP Server 包：agent-service 访问数据的唯一通道。

工具语义直接复用 `src.services` 的业务层，避免 REST 与 MCP 出现两套规则。
挂载方式：`FastMCP(streamable_http_path="/mcp", stateless_http=True)`，
其路由并入 FastAPI 应用，session manager 在应用 lifespan 内启动。
"""

from src.mcp.server import mcp_server, mount_mcp, session_factory

__all__ = ["mcp_server", "mount_mcp", "session_factory"]
