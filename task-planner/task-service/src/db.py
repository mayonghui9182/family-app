"""数据库引擎与会话管理（异步 asyncpg 驱动）。

连接串由 `src.config.settings.sqlalchemy_url` 提供：
优先读环境变量 `DATABASE_URL`，否则由 `POSTGRES_*` 组装。
"""

from collections.abc import AsyncGenerator

from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from src.config import settings
from src.ownership import ensure_category

# 全局异步引擎：pool_pre_ping 避免连接被数据库侧关闭后复用报错
engine = create_async_engine(settings.sqlalchemy_url, pool_pre_ping=True)

# 会话工厂：expire_on_commit=False 便于 commit 后继续读取 ORM 属性
SessionLocal = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)


async def get_session() -> AsyncGenerator[AsyncSession, None]:
    """FastAPI 依赖：提供请求级数据库会话，请求结束自动关闭。"""
    async with SessionLocal() as session:
        await ensure_category(session)
        yield session
