"""tool-service 入口：FastAPI 路由 + /healthz。"""
from typing import Any

from fastapi import FastAPI

from .routes.weather_routes import router as weather_router
from .services.weather_service import weather_service


def create_app() -> FastAPI:
    app = FastAPI(title="family-app tool-service", version="0.1.0")
    app.include_router(weather_router)

    @app.get("/healthz", tags=["ops"])
    async def healthz() -> dict[str, Any]:
        return {"status": "ok", "cache_size": len(weather_service._cache)}  # noqa: SLF001

    return app


app = create_app()
