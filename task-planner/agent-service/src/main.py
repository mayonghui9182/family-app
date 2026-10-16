"""agent-service 应用入口。

职责：
- 创建 FastAPI 实例，暴露 `/healthz` 存活探针
- 挂载会话拆解 SSE 入口（`POST /api/chat`）与总结手动触发（`POST /api/summary/trigger`）
- 在 lifespan 内启动/停止每日 22:00 总结调度
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager, suppress

from fastapi import FastAPI

from src.config import settings
from src.work_auth import WorkAuthMiddleware
from src.logging_setup import setup_logging
from src.routes.chat import router as chat_router
from src.routes.summary import router as summary_router
from src.scheduler import build_scheduler

# 进程启动即生效：一行一 JSON 的结构化日志（7.1 验收点）
setup_logging(settings.log_level)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    """应用生命周期：启动/停止每日 22:00 总结调度（9.5）。"""
    scheduler = build_scheduler()
    scheduler.start()
    try:
        yield
    finally:
        with suppress(Exception):  # 关停失败不阻塞进程退出
            scheduler.shutdown(wait=False)


app = FastAPI(
    title="task-planner · agent-service",
    version="0.1.0",
    description="工作任务计划系统：AI 任务拆解、每日总结与模板自学习（无状态）",
    lifespan=lifespan,
)

# 会话拆解 SSE 入口（8.1）与总结手动触发（9.5）
app.include_router(chat_router)
app.add_middleware(WorkAuthMiddleware, backend_url=settings.backend_base_url,
                   internal_secret=settings.internal_api_secret)
app.include_router(summary_router)


@app.get("/healthz")
async def healthz() -> dict:
    """存活探针：供容器 healthcheck 与 compose depends_on 判定使用。"""
    return {"status": "ok", "service": "agent-service", "log_level": settings.log_level}
