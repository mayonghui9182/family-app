"""wechat-service FastAPI 入口（design.md D1/D5）。

无 DB、无 SDK；对微信协议全部手写 httpx 调用，对 backend 走内部连接器。
create_app 支持测试注入 httpx client（MockTransport 全离线）；
app.state 在构造时装配（ASGI 测试不触发 lifespan），lifespan 仅负责生产 client 关闭。
"""
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI

from .connectors.backend_client import BackendClient
from .routes.internal_routes import router as internal_router
from .routes.wechat_routes import OAuthStateStore
from .routes.wechat_routes import router as wechat_router
from .settings import Settings, get_settings


def create_app(
    settings: Settings | None = None,
    backend: BackendClient | None = None,
    wechat_client: httpx.AsyncClient | None = None,
) -> FastAPI:
    """构造 app；测试可注入 mock client，生产由 lifespan 管理真实 client 生命周期。"""
    settings = settings or get_settings()
    owns_clients = backend is None and wechat_client is None

    wx_client = wechat_client or httpx.AsyncClient(
        base_url=settings.wechat_api_base, timeout=10.0
    )
    backend_client = backend or BackendClient(settings=settings)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        try:
            yield
        finally:
            if owns_clients:
                await wx_client.aclose()
                await backend_client.aclose()

    app = FastAPI(title="wechat-service", lifespan=lifespan)
    app.state.settings = settings
    app.state.oauth_states = OAuthStateStore()
    app.state.wechat_client = wx_client
    app.state.backend = backend_client

    app.include_router(wechat_router)
    app.include_router(internal_router)

    @app.get("/healthz")
    async def healthz() -> dict[str, str]:
        return {"status": "ok"}

    return app


app = create_app()
