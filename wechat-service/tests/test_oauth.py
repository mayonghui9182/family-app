"""OAuth 授权分流（6 用例）：授权跳转/已绑定 fragment token/未绑定票据页/state 拒绝/微信故障降级。"""
from urllib.parse import parse_qs, urlparse

import httpx


def wechat_oauth_handler(openid: str = "o-wx-user-1", fail: bool = False):
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/sns/oauth2/access_token":
            if fail:
                return httpx.Response(200, json={"errcode": 40029, "errmsg": "invalid code"})
            return httpx.Response(
                200,
                json={"access_token": "wx-at", "expires_in": 7200, "openid": openid},
            )
        return httpx.Response(404)

    return handler


def backend_handler(mode: str = "bound"):
    """mode: bound → 免密登录成功；unbound → 40115 未绑定 + 票据签发。"""

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/api/internal/wechat/login"):
            if mode == "bound":
                return httpx.Response(
                    200,
                    json={"code": 0, "data": {"accessToken": "fam-token-abc", "nickname": "老人", "families": []}},
                )
            return httpx.Response(200, json={"code": 40115, "msg": "微信还没绑定账号", "data": None})
        if request.url.path.endswith("/api/internal/wechat/ticket"):
            return httpx.Response(200, json={"code": 0, "data": {"ticket": "TICKET123"}})
        return httpx.Response(404)

    return handler


def state_from(location: str) -> str:
    query = parse_qs(urlparse(location).query)
    return query["state"][0]


async def test_authorize_redirects_with_random_state(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    client = asgi_factory()

    r1 = await client.get("/wechat/authorize")
    r2 = await client.get("/wechat/authorize")

    assert r1.status_code == 302
    loc = r1.headers["location"]
    assert loc.startswith("https://open.weixin.test/connect/oauth2/authorize")
    assert "scope=snsapi_base" in loc
    assert state_from(loc)
    # state 每次随机（防猜解/CSRF）
    assert state_from(r1.headers["location"]) != state_from(r2.headers["location"])


async def test_bound_openid_redirects_h5_with_token_in_fragment(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    client = asgi_factory(
        backend_handler=backend_handler("bound"),
        wechat_handler=wechat_oauth_handler(),
    )
    state = state_from((await client.get("/wechat/authorize")).headers["location"])

    resp = await client.get("/wechat/oauth/callback", params={"code": "wx-code-1", "state": state})

    assert resp.status_code == 302
    loc = resp.headers["location"]
    assert loc.startswith("https://tunnel.example.com/wechat/callback#token=")
    assert "fam-token-abc" in loc
    # token 在 fragment（# 后），不进 query string
    assert "token" not in urlparse(loc).query


async def test_unbound_openid_redirects_bind_page_with_ticket(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    client = asgi_factory(
        backend_handler=backend_handler("unbound"),
        wechat_handler=wechat_oauth_handler(),
    )
    state = state_from((await client.get("/wechat/authorize")).headers["location"])

    resp = await client.get("/wechat/oauth/callback", params={"code": "wx-code-2", "state": state})

    assert resp.status_code == 302
    loc = resp.headers["location"]
    assert loc == "https://tunnel.example.com/wechat/bind?ticket=TICKET123"
    # openid 绝不出现在跳转 URL
    assert "o-wx-user-1" not in loc


async def test_state_mismatch_rejected_with_chinese_page(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    client = asgi_factory(
        backend_handler=backend_handler("bound"),
        wechat_handler=wechat_oauth_handler(),
    )

    resp = await client.get("/wechat/oauth/callback", params={"code": "wx-code-3", "state": "forged-state"})

    assert resp.status_code == 400
    assert "公众号菜单" in resp.text


async def test_state_single_use_second_call_rejected(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    client = asgi_factory(
        backend_handler=backend_handler("bound"),
        wechat_handler=wechat_oauth_handler(),
    )
    state = state_from((await client.get("/wechat/authorize")).headers["location"])

    first = await client.get("/wechat/oauth/callback", params={"code": "wx-code-4", "state": state})
    second = await client.get("/wechat/oauth/callback", params={"code": "wx-code-4", "state": state})

    assert first.status_code == 302
    assert second.status_code == 400


async def test_wechat_api_error_degrades_to_chinese_page(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    client = asgi_factory(
        backend_handler=backend_handler("bound"),
        wechat_handler=wechat_oauth_handler(fail=True),
    )
    state = state_from((await client.get("/wechat/authorize")).headers["location"])

    resp = await client.get("/wechat/oauth/callback", params={"code": "bad-code", "state": state})

    assert resp.status_code == 502
    assert "微信" in resp.text
    assert "菜单" in resp.text


async def test_healthz_ok(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    client = asgi_factory()
    resp = await client.get("/healthz")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"
