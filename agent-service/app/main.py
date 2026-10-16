"""agent-service 入口：FastAPI 应用工厂 + 统一异常处理 + /healthz + 22:00 总结调度装配。"""

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from loguru import logger

from .models.errors import AgentServiceError
from .routes import chat_routes, internal_routes, summary_routes
from .schedulers import summary_job
from .utils.log import setup_logging

_HTTP_STATUS: dict[int, int] = {
    40099: 400,
    40102: 401,
    40103: 401,
    40302: 403,
    46000: 403,
    46502: 400,
    56001: 504,
    56002: 502,
    56003: 502,
}


def create_app() -> FastAPI:
    """应用工厂（便于测试多次构建）。"""
    setup_logging()
    app = FastAPI(title="family-app agent-service", version="0.1.0")
    app.include_router(chat_routes.router)
    app.include_router(internal_routes.router)
    app.include_router(summary_routes.router)

    @app.get("/healthz", tags=["ops"])
    async def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.exception_handler(AgentServiceError)
    async def agent_error_handler(request: Request, exc: AgentServiceError) -> JSONResponse:
        logger.warning("business error code={} path={}", exc.code, request.url.path)
        return JSONResponse(
            status_code=_HTTP_STATUS.get(exc.code, 500),
            content={"code": exc.code, "msg": exc.message},
        )

    @app.on_event("startup")
    async def _start_scheduler() -> None:
        """装配 22:00 总结 cron（Asia/Shanghai）。测试用 ASGITransport 不触发 lifespan。"""
        scheduler = AsyncIOScheduler(timezone="Asia/Shanghai")
        summary_job.configure(summary_routes.get_summarizer())
        if summary_job.register_summary_job(scheduler):
            scheduler.start()
            app.state.scheduler = scheduler

    @app.on_event("shutdown")
    async def _stop_scheduler() -> None:
        scheduler = getattr(app.state, "scheduler", None)
        if scheduler is not None:
            scheduler.shutdown(wait=False)

    return app


app = create_app()
