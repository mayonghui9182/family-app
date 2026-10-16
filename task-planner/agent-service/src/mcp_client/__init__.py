"""MCP Client 包：连接 task-service 的 `/mcp`（Streamable HTTP）。

agent-service 不直连数据库，所有读写都经此层的工具调用完成；
每次调用记录工具名与耗时（结构化日志）。
"""
