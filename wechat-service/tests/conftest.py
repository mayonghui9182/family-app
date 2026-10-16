"""测试公共夹具：全离线（httpx MockTransport mock 出站、ASGITransport 打 app）。"""
import os
from collections.abc import Callable
from typing import Any

import httpx
import pytest

# 测试必须显式提供内部密钥，不能依赖生产默认值。
os.environ.setdefault("INTERNAL_API_SECRET", "test-internal-secret")

from app.connectors.backend_client import BackendClient
from app.main import create_app
from app.settings import Settings

# 出站请求处理器：收到 httpx.Request，返回 httpx.Response
Handler = Callable[[httpx.Request], httpx.Response]


def make_settings(**overrides: Any) -> Settings:
    base: dict[str, Any] = {
        "wechat_app_id": "wxTESTAPPID",
        "wechat_app_secret": "test-secret",
        "wechat_token": "callback-token",
        "oauth_callback_base": "https://tunnel.example.com",
        "h5_base_url": "https://tunnel.example.com",
        "wechat_api_base": "https://api.weixin.test",
        "wechat_open_base": "https://open.weixin.test",
        "backend_base_url": "http://backend.test",
        "internal_api_secret": "itest-secret",
    }
    base.update(overrides)
    return Settings(**base)


def _boom(_request: httpx.Request) -> httpx.Response:
    """未预期的出站调用兜底：599 让测试立刻暴露漏 mock。"""
    return httpx.Response(599, text="unexpected outbound call")


@pytest.fixture
async def asgi_factory() -> Callable[..., httpx.AsyncClient]:
    """构造挂载了 mock 出站 client 的 ASGI httpx client。

    用法：client = asgi_factory(backend_handler=..., wechat_handler=...)
    """
    created: list[httpx.AsyncClient] = []

    def factory(
        backend_handler: Handler | None = None,
        wechat_handler: Handler | None = None,
        settings: Settings | None = None,
    ) -> httpx.AsyncClient:
        s = settings or make_settings()
        wx = httpx.AsyncClient(
            transport=httpx.MockTransport(wechat_handler or _boom),
            base_url=s.wechat_api_base,
        )
        be = httpx.AsyncClient(
            transport=httpx.MockTransport(backend_handler or _boom),
            base_url=s.backend_base_url,
        )
        backend = BackendClient(client=be, settings=s)
        app = create_app(settings=s, backend=backend, wechat_client=wx)
        client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://asgi.test")
        created.append(client)
        return client

    yield factory
    for client in created:
        await client.aclose()
