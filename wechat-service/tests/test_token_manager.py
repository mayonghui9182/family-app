"""access_token 单飞缓存（3 用例）：并发单飞、临期主动续期、刷新失败保留旧值退避。"""
import asyncio

import httpx

from app.settings import Settings
from app.wechat.oauth import WechatApiError
from app.wechat.token_manager import WechatTokenManager

from .conftest import make_settings


def make_manager(handler, settings: Settings | None = None) -> WechatTokenManager:
    s = settings or make_settings()
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url=s.wechat_api_base)
    return WechatTokenManager(client, s)


def token_handler(calls: dict[str, int], expires_in: int = 7200):
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/cgi-bin/token"
        calls["n"] += 1
        return httpx.Response(200, json={"access_token": f"AT-{calls['n']}", "expires_in": expires_in})

    return handler


async def test_10_concurrent_requests_single_http_call() -> None:
    calls: dict[str, int] = {"n": 0}
    manager = make_manager(token_handler(calls))

    tokens = await asyncio.gather(*[manager.get_token() for _ in range(10)])

    assert calls["n"] == 1, "单飞：10 并发只应发起 1 次 HTTP"
    assert all(t == "AT-1" for t in tokens)


async def test_refresh_when_within_300s_leeway() -> None:
    calls: dict[str, int] = {"n": 0}
    # expires_in=200 < 300s 续期阈值：第二次 get_token 必须主动续期
    manager = make_manager(token_handler(calls, expires_in=200))

    first = await manager.get_token()
    second = await manager.get_token()

    assert first == "AT-1"
    assert second == "AT-2"
    assert calls["n"] == 2


async def test_refresh_failure_keeps_old_token_with_backoff() -> None:
    calls: dict[str, int] = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] == 1:
            return httpx.Response(200, json={"access_token": "AT-OLD", "expires_in": 200})
        return httpx.Response(500, text="wechat boom")

    manager = make_manager(handler)

    first = await manager.get_token()
    # 第二次触发续期 → HTTP 500 → 保留旧值
    second = await manager.get_token()
    # 退避期内第三次：不应再发 HTTP
    third = await manager.get_token()

    assert first == "AT-OLD"
    assert second == "AT-OLD"
    assert third == "AT-OLD"
    assert calls["n"] == 2, "刷新失败后退避，第三次调用不应发 HTTP"


async def test_first_fetch_failure_raises() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="wechat boom")

    manager = make_manager(handler)
    try:
        await manager.get_token()
        raise AssertionError("无旧 token 时刷新失败应抛 WechatApiError")
    except WechatApiError:
        pass
