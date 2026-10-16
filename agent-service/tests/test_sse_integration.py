"""A-1 SSE 端到端（ASGI 内存传输）+ 鉴权短路 + B-1 内部路由契约。"""
import httpx
import pytest
from fastapi import FastAPI

from app.main import create_app
from app.models.errors import AuthError
from app.routes import chat_routes
from tests.conftest import CTX_FULL, VALID_DECOMPOSE_JSON, FakeLLM, make_decomposer


def build_app(llm: FakeLLM, ctx: dict, auth_error: Exception | None = None) -> FastAPI:
    dec, _ = make_decomposer(llm, ctx, auth_error=auth_error)
    application = create_app()
    application.dependency_overrides[chat_routes.get_decomposer] = lambda: dec
    return application


def parse_events(text: str) -> list[tuple[str, str]]:
    events: list[tuple[str, str]] = []
    for block in text.split("\n\n"):
        if not block.strip():
            continue
        ev = next(line for line in block.splitlines() if line.startswith("event: ")).removeprefix("event: ")
        data = next(line for line in block.splitlines() if line.startswith("data: ")).removeprefix("data: ")
        events.append((ev, data))
    return events


AUTH_HEADERS = {"X-Family-Id": "1", "X-Access-Token": "tok"}


async def test_a1_stream_happy_path() -> None:
    llm = FakeLLM([
        {"kind": "json", "text": '{"category_id": 3}'},
        {"kind": "json", "tokens": ["a", "b"], "text": VALID_DECOMPOSE_JSON},
    ])
    application = build_app(llm, CTX_FULL)
    transport = httpx.ASGITransport(app=application)
    async with httpx.AsyncClient(transport=transport, base_url="http://t") as client:
        resp = await client.post("/api/v1/chat/stream", json={"message": "周末大扫除"}, headers=AUTH_HEADERS)
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("text/event-stream")
    names = [ev for ev, _ in parse_events(resp.text)]
    # 快流下 writer.drain() 将全部 token 合为一帧 reasoning
    assert names == ["classification", "reasoning", "proposal", "done"]
    # 帧格式紧凑 JSON 断言（与 format_sse 输出一致）
    assert '\ndata: {"category":"家务"}\n' in resp.text


async def test_a1_auth_fail_short_circuits_no_llm() -> None:
    llm = FakeLLM([])
    application = build_app(llm, CTX_FULL, auth_error=AuthError())
    transport = httpx.ASGITransport(app=application)
    async with httpx.AsyncClient(transport=transport, base_url="http://t") as client:
        resp = await client.post("/api/v1/chat/stream", json={"message": "x"}, headers=AUTH_HEADERS)
    events = parse_events(resp.text)
    assert [ev for ev, _ in events] == ["error"]
    assert '"code":40103' in events[0][1]
    assert llm.calls == []  # 身份失败绝不调 LLM


async def test_healthz() -> None:
    application = build_app(FakeLLM([]), CTX_FULL)
    transport = httpx.ASGITransport(app=application)
    async with httpx.AsyncClient(transport=transport, base_url="http://t") as client:
        resp = await client.get("/healthz")
    assert resp.status_code == 200 and resp.json() == {"status": "ok"}


async def test_b1_internal_contract(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("INTERNAL_API_SECRET", "itest-secret")
    from app.settings import get_settings

    get_settings.cache_clear()
    llm = FakeLLM([
        {"kind": "json", "text": '{"category_id": 3}'},  # 分类调用
        {"kind": "raw", "text": VALID_DECOMPOSE_JSON},  # 拆解调用
    ])
    application = build_app(llm, CTX_FULL)
    headers = {"X-Internal-Secret": ""}
    transport = httpx.ASGITransport(app=application)
    async with httpx.AsyncClient(transport=transport, base_url="http://t") as client:
        bad = await client.post("/api/internal/ai-decompose", json={"title": "t", "family_id": 1}, headers=headers)
        good = await client.post(
            "/api/internal/ai-decompose",
            json={"title": "周末大扫除", "description": "", "category_id": None, "family_id": 7, "fallback": True},
            headers={"X-Internal-Secret": "itest-secret"},
        )
    assert bad.status_code == 401 and bad.json()["code"] == 40102
    # 秘钥错误时同样不调 LLM
    assert good.status_code == 200
    body = good.json()
    assert set(body.keys()) == {"decomposed", "task_id", "message"}
    assert body["decomposed"] is True and body["task_id"] == 501 and body["message"] is None
