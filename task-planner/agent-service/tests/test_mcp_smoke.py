"""MCP 连通性 smoke test（tasks.md 6.4）。

agent-service 侧 MCP Client 连接 `MCP_TASK_SERVICE_URL`，完成一次
`get_categories` 往返：封套 code=0、data 为列表，且日志记录工具名与耗时。

前置：`docker compose -p task-planner up -d --build`（task-service 已挂载 /mcp）。
容器外运行时需设置 `MCP_TASK_SERVICE_URL=http://localhost:8100/mcp`
（默认值 `http://task-service:8100/mcp` 仅在 compose 网络内可解析）。
"""

import logging
import os
import pytest
import httpx
from src.work_auth import WorkIdentity, current_identity

from src.config import settings
from src.mcp_client.client import call_mcp_tool


async def test_get_categories_roundtrip(caplog):
    """get_categories 往返成功，且日志记录工具名与耗时。"""
    with caplog.at_level(logging.INFO, logger="agent_service.mcp_client"):
        result = await call_mcp_tool(
            settings.mcp_task_service_url,
            "get_categories",
            timeout_seconds=settings.mcp_timeout_seconds,
        )

    assert result.isError is False, f"协议级错误：{result.content}"
    body = result.structuredContent
    assert body["code"] == 0
    assert isinstance(body["data"], list)

    # 日志必须记录工具名与耗时（6.4 验收点）
    messages = [record.getMessage() for record in caplog.records]
    assert any(
        "get_categories" in message and "elapsed_ms" in message
        for message in messages
    ), f"日志缺少工具名或耗时记录：{messages}"


@pytest.mark.skipif(os.environ.get("TP_E2E_ALLOW_WRITES") != "1", reason="需要显式授权的隔离测试服务")
async def test_real_mcp_refuses_cross_user_task_detail():
    """真实 HTTP MCP 不能凭另一个有效账号读取私人工作。"""
    created = await call_mcp_tool(settings.mcp_task_service_url, "create_task", {"title": "隔离验收私人任务"})
    task_id = created.structuredContent["data"]["id"]
    token = current_identity.set(WorkIdentity("2", {"X-Access-Token": "b" * 64}))
    try:
        result = await call_mcp_tool(settings.mcp_task_service_url, "get_task_detail", {"task_id": task_id})
        assert result.structuredContent["code"] != 0
        assert result.structuredContent.get("data") is None
    finally:
        current_identity.reset(token)
        base = settings.mcp_task_service_url.rsplit("/mcp", 1)[0]
        async with httpx.AsyncClient() as client:
            response = await client.delete(base + "/api/tasks/" + task_id,
                                          headers={"X-Access-Token": "a" * 64})
            assert response.status_code == 200


async def test_real_service_rejects_forged_identity_without_token():
    """绕过网关直连服务也必须验证凭据。"""
    base = settings.mcp_task_service_url.rsplit("/mcp", 1)[0]
    async with httpx.AsyncClient() as client:
        response = await client.get(base + "/api/tasks", headers={"X-Work-User-Id": "1"})
        assert response.status_code == 401
