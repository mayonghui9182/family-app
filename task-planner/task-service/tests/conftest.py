"""pytest 共享夹具。

测试库指向开发用 Postgres（tp-dev-pg，宿主端口 55432）的 task_db_test：
- 必须在导入 src.db / src.config 之前设置 DATABASE_URL 环境变量，
  否则 settings 的 lru_cache 会固化开发库连接串；
- 每个用例前 drop_all + create_all 重建全部表，保证隔离。
"""

import os

from sqlalchemy.engine import make_url

# 测试独占连接配置，拒绝继承业务 DATABASE_URL，避免清空真实数据。
test_url = os.environ.get("TEST_DATABASE_URL", "postgresql+asyncpg://tp:tp@127.0.0.1:55432/task_db_test")
if not (make_url(test_url).database or "").endswith("_test"):
    raise RuntimeError("测试数据库名称必须以 _test 结尾")
os.environ["DATABASE_URL"] = test_url

import asyncio

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from src.db import get_session
from src.main import app as fastapi_app
from src.models import Base
from src.ownership import owner_id
from src.work_auth import WorkIdentity


@pytest.fixture(autouse=True)
def authenticated_work_scope(monkeypatch):
    """旧功能测试使用明确测试身份，生产代码没有默认用户旁路。"""
    async def identity(headers, **kwargs):
        user = "2" if headers.get("x-access-token") == "b" * 64 else "1"
        return WorkIdentity(user, {"X-Access-Token": headers.get("x-access-token", "a" * 64)})
    monkeypatch.setattr("src.work_auth.verify_identity", identity)
    token = owner_id.set("1")
    yield
    owner_id.reset(token)


@pytest.fixture()
async def session():
    """提供独立数据库会话：用例前重建全部表，用例后释放引擎。"""
    engine = create_async_engine(os.environ["DATABASE_URL"])
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
        await conn.run_sync(Base.metadata.create_all)
    maker = async_sessionmaker(engine, expire_on_commit=False)
    async with maker() as sess:
        yield sess
    await engine.dispose()


@pytest.fixture()
def client():
    """REST 测试客户端：用例前重建全部表，请求会话指向测试库。

    两处引擎均用 NullPool（连接用完即关）：
    - TestClient 在自带的 portal 事件循环中执行请求，
      连接池若跨循环复用 asyncpg 连接会报 "attached to a different loop"；
    - 建表引擎在 asyncio.run 的临时循环中运行，同理不复用连接。
    """

    async def _recreate_schema() -> None:
        engine = create_async_engine(os.environ["DATABASE_URL"], poolclass=NullPool)
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.drop_all)
            await conn.run_sync(Base.metadata.create_all)
        await engine.dispose()

    asyncio.run(_recreate_schema())

    request_engine = create_async_engine(os.environ["DATABASE_URL"], poolclass=NullPool)
    request_maker = async_sessionmaker(request_engine, expire_on_commit=False)

    async def _override_get_session():
        async with request_maker() as sess:
            yield sess

    fastapi_app.dependency_overrides[get_session] = _override_get_session
    with TestClient(fastapi_app) as test_client:
        yield test_client
    fastapi_app.dependency_overrides.clear()


@pytest.fixture()
async def db(client):
    """造数会话：在 client 重建表之后提供直连数据库的会话。

    用于 REST 无法覆盖的场景：写入分类、把 draft 的 expires_at 改到过去、
    直接插子待办/notes 并断言删除级联后的库内残留。
    """
    engine = create_async_engine(os.environ["DATABASE_URL"], poolclass=NullPool)
    maker = async_sessionmaker(engine, expire_on_commit=False)
    async with maker() as sess:
        yield sess
    await engine.dispose()


@pytest.fixture()
async def mcp_session():
    """MCP 内存通道连接工厂（6.1-6.3 工具测试共用）。

    关键约束：`create_connected_server_and_client_session` 内部用 anyio TaskGroup
    托管服务器任务，而 TaskGroup 必须在「同一个 asyncio 任务」中进入与退出。
    pytest-asyncio 会在不同任务里执行夹具的 setup 与 teardown，若在夹具里
    `yield` 于 TaskGroup 之内，teardown 退出时就会报
    "Attempted to exit cancel scope in a different task"。
    因此本夹具只做建表与工厂注入，真正的连接放到用例体内（单一任务）建立：
    `async with mcp_session() as session:`。

    流程：
    1. 重建全部表，保证用例隔离；
    2. 把 `src.mcp.server.session_factory` 换成 NullPool 会话工厂：
       工具在测试事件循环内执行，连接池跨循环复用 asyncpg 连接会报
       "attached to a different loop"，NullPool 用完即关可规避；
    3. yield 一个异步上下文管理器工厂，用例体内 `async with` 打开会话
       （内存通道直接驱动低层 `server.run()`，与 Streamable HTTP 的
       session manager 无关，无需启动 manager）；
    4. 用例结束恢复原工厂并释放引擎。
    """
    from contextlib import asynccontextmanager

    import src.mcp.server as mcp_server_module
    from mcp.shared.memory import create_connected_server_and_client_session

    engine = create_async_engine(os.environ["DATABASE_URL"], poolclass=NullPool)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
        await conn.run_sync(Base.metadata.create_all)
    maker = async_sessionmaker(engine, expire_on_commit=False)

    original_factory = mcp_server_module.session_factory
    mcp_server_module.session_factory = maker

    @asynccontextmanager
    async def _connect():
        async with create_connected_server_and_client_session(
            mcp_server_module.mcp_server
        ) as session:
            yield session

    try:
        yield _connect
    finally:
        mcp_server_module.session_factory = original_factory
        await engine.dispose()


@pytest.fixture()
async def mcp_db(mcp_session):
    """MCP 测试的造数会话：直连 mcp_session 刚重建好的测试库。

    用于工具无法覆盖的前置数据：过期 draft、completed 任务与子待办、
    模板初始行等。与工具共用同一张表（同一测试库）。
    """
    engine = create_async_engine(os.environ["DATABASE_URL"], poolclass=NullPool)
    maker = async_sessionmaker(engine, expire_on_commit=False)
    async with maker() as sess:
        yield sess
    await engine.dispose()
