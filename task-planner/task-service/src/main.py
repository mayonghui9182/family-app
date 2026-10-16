"""task-service 应用入口。

职责：
- 创建 FastAPI 实例，暴露 `/healthz` 存活探针
- 挂载 REST 路由（`src.routes`）与 MCP Server（`src.mcp`）
- 统一响应封套与异常处理（`src.services.envelope`）
"""

from __future__ import annotations

from fastapi import FastAPI, Header, HTTPException
import hmac
from sqlalchemy import text
from src.db import engine

from src.config import settings
from src.ownership import owner_id
from src.work_auth import WorkAuthMiddleware
from src.mcp.server import mount_mcp
from src.routes.categories import router as categories_router
from src.routes.notes import router as notes_router
from src.routes.subtasks import router as subtasks_router
from src.routes.tasks import router as tasks_router
from src.routes.templates import router as templates_router
from src.services.envelope import register_exception_handlers

app = FastAPI(
    title="task-planner · task-service",
    version="0.1.0",
    description="工作任务计划系统：任务持久化、REST API 与 MCP Server（唯一连库服务）",
)

# 统一封套：任何异常都转成 {"code","msg","data"} 结构（design.md D4）
register_exception_handlers(app)
app.add_middleware(WorkAuthMiddleware, backend_url=settings.backend_base_url,
                   internal_secret=settings.internal_api_secret, bind_owner=owner_id)

# REST 路由统一挂载到 /api 前缀
app.include_router(tasks_router, prefix="/api")
app.include_router(subtasks_router, prefix="/api")
app.include_router(notes_router, prefix="/api")
app.include_router(categories_router, prefix="/api")
app.include_router(templates_router, prefix="/api")

# MCP Server：agent-service 访问数据的唯一通道（tasks.md 6.1-6.3）
mount_mcp(app)


@app.get("/healthz")
async def healthz() -> dict:
    """存活探针：供容器 healthcheck 与 compose depends_on 判定使用。"""
    return {"status": "ok", "service": "task-service", "log_level": settings.log_level}


@app.get("/internal/work-users")
async def work_users(x_internal_secret: str = Header(default="")):
    """仅机器调用枚举作用域，不返回任务或用户资料。"""
    if not settings.internal_api_secret or not hmac.compare_digest(
            x_internal_secret, settings.internal_api_secret):
        raise HTTPException(401, "内部认证失败")
    async with engine.connect() as connection:
        rows = await connection.execute(text(
            "SELECT DISTINCT user_id FROM categories WHERE user_id <> 'default' ORDER BY user_id"))
        return {"userIds": [row[0] for row in rows if row[0].isdigit()]}
