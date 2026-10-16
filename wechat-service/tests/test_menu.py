"""公众号菜单创建（2 用例）：errcode=0 成功；无密钥 403。"""
import json

import httpx


def wechat_menu_handler(seen: dict[str, object]):
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/cgi-bin/token":
            return httpx.Response(200, json={"access_token": "MENU-TOKEN", "expires_in": 7200})
        if request.url.path == "/cgi-bin/menu/create":
            seen["token_param"] = request.url.params.get("access_token")
            seen["body"] = request.content
            return httpx.Response(200, json={"errcode": 0, "errmsg": "ok"})
        return httpx.Response(404)

    return handler


async def test_setup_menu_success_fetches_token_then_creates(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    seen: dict[str, object] = {}
    client = asgi_factory(wechat_handler=wechat_menu_handler(seen))

    resp = await client.post("/internal/setup-menu", headers={"X-Internal-Secret": "itest-secret"})

    assert resp.status_code == 200
    assert resp.json()["code"] == 0
    assert seen["token_param"] == "MENU-TOKEN"
    # 菜单体含两个按钮（家庭应用 / 微信绑定），URL 指向隧道域名
    payload = json.loads(bytes(seen["body"]).decode("utf-8"))  # type: ignore[arg-type]
    names = [b["name"] for b in payload["button"]]
    assert "家庭应用" in names
    assert any(b["url"].endswith("/wechat/authorize") for b in payload["button"])


async def test_setup_menu_without_secret_returns_403(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    client = asgi_factory(wechat_handler=wechat_menu_handler({}))

    resp = await client.post("/internal/setup-menu")

    assert resp.status_code == 403
    assert resp.json()["code"] == 40300
