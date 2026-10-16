"""客服消息文本下发（Change 14 D6）：批量端点 / 40001 重试一次 / 单条隔离 / 永不 5xx。

为什么用客服消息而不是模板消息：模板消息要在公众平台预先申请模板 id，用户侧还要能看懂「服务通知」；
客服消息在用户与公众号交互后 48 小时内免申请直接下发，而 Change 14 的回推场景正是「用户刚发过消息」。
"""
import json
from collections.abc import Callable
from typing import Any

import httpx

KEFU_PATH = "/cgi-bin/message/custom/send"
SECRET = {"X-Internal-Secret": "itest-secret"}


def wechat_kefu_handler(
    seen: dict[str, Any],
    send_behavior: Callable[[int], httpx.Response] | None = None,
    token_behavior: Callable[[int], httpx.Response] | None = None,
) -> Callable[[httpx.Request], httpx.Response]:
    """记录 token/send 调用次数与报文；behavior(第几次调用) 可定制返回，未定制则一律成功。"""

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/cgi-bin/token":
            seen["token"] = seen.get("token", 0) + 1
            if token_behavior is not None:
                return token_behavior(seen["token"])
            tok = "TOKEN-2" if seen["token"] > 1 else "TOKEN-1"
            return httpx.Response(200, json={"access_token": tok, "expires_in": 7200})
        if request.url.path == KEFU_PATH:
            seen["send"] = seen.get("send", 0) + 1
            seen.setdefault("bodies", []).append(json.loads(request.content))
            seen.setdefault("tokens", []).append(request.url.params.get("access_token"))
            if send_behavior is not None:
                return send_behavior(seen["send"])
            return httpx.Response(200, json={"errcode": 0, "errmsg": "ok", "msgid": 999})
        return httpx.Response(404)

    return handler


async def test_send_text_posts_wechat_custom_send_shape(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    """正常发送：报文必须是微信规定的 {touser, msgtype:text, text:{content}} 形态。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(wechat_handler=wechat_kefu_handler(seen))

    resp = await client.post(
        "/internal/kefu/send",
        headers=SECRET,
        json={"messages": [{"openid": "oAAA", "content": "您的任务草稿已生成，点击查看：https://x/1"}]},
    )

    assert resp.status_code == 200
    assert resp.json()["code"] == 0
    assert seen["send"] == 1
    body = seen["bodies"][0]
    assert body["touser"] == "oAAA"
    assert body["msgtype"] == "text"
    assert body["text"]["content"] == "您的任务草稿已生成，点击查看：https://x/1"
    assert seen["tokens"] == ["TOKEN-1"]


async def test_batch_shares_one_token_fetch(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    """同批多条只取一次 access_token（微信 token quota 2000 次/日，批量复用省额度）。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(wechat_handler=wechat_kefu_handler(seen))

    resp = await client.post(
        "/internal/kefu/send",
        headers=SECRET,
        json={"messages": [
            {"openid": "o1", "content": "第一条"},
            {"openid": "o2", "content": "第二条"},
        ]},
    )

    assert resp.status_code == 200
    results = resp.json()["results"]
    assert [r["openid"] for r in results] == ["o1", "o2"]
    assert all(r["ok"] for r in results)
    assert seen["token"] == 1
    assert seen["send"] == 2


async def test_40001_refreshes_token_and_retries_once(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    """access_token 失效：强制刷新后换 token 重试一次，成功即 ok=True。"""
    seen: dict[str, Any] = {}

    def behavior(n: int) -> httpx.Response:
        if n == 1:
            return httpx.Response(200, json={"errcode": 40001, "errmsg": "invalid credential"})
        return httpx.Response(200, json={"errcode": 0, "errmsg": "ok", "msgid": 1})

    client = asgi_factory(wechat_handler=wechat_kefu_handler(seen, behavior))

    resp = await client.post(
        "/internal/kefu/send",
        headers=SECRET,
        json={"messages": [{"openid": "oR", "content": "重试我"}]},
    )

    assert resp.status_code == 200
    assert resp.json()["results"][0]["ok"] is True
    assert seen["token"] == 2
    assert seen["send"] == 2
    assert seen["tokens"] == ["TOKEN-1", "TOKEN-2"]


async def test_40001_persistent_stops_after_single_retry(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    """刷新后仍 40001：只重试一次就收手（不循环刷 token 打爆额度），并把失败码带回。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(
        wechat_handler=wechat_kefu_handler(
            seen, lambda _n: httpx.Response(200, json={"errcode": 40001, "errmsg": "invalid credential"})
        )
    )

    resp = await client.post(
        "/internal/kefu/send",
        headers=SECRET,
        json={"messages": [{"openid": "oR2", "content": "一直失效"}]},
    )

    assert resp.status_code == 200
    result = resp.json()["results"][0]
    assert result["ok"] is False
    assert result["errcode"] == 40001
    assert seen["send"] == 2
    assert seen["token"] == 2


async def test_retry_after_refresh_network_error_is_caught(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    """刷新后重发时撞上网络异常：仍按单条失败收口（中文提示），绝不让异常冒到端点外。"""
    seen: dict[str, Any] = {}

    def behavior(n: int) -> httpx.Response:
        if n == 1:
            return httpx.Response(200, json={"errcode": 40001, "errmsg": "invalid credential"})
        raise httpx.ConnectError("重发时链路断了")

    client = asgi_factory(wechat_handler=wechat_kefu_handler(seen, behavior))

    resp = await client.post(
        "/internal/kefu/send",
        headers=SECRET,
        json={"messages": [{"openid": "oN", "content": "刷新后炸"}]},
    )

    assert resp.status_code == 200
    result = resp.json()["results"][0]
    assert result["ok"] is False
    assert "刷新后重试失败" in (result["errmsg"] or "")
    assert seen["send"] == 2


async def test_43004_unsubscribed_is_not_retried(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    """用户未关注/已取关（43004）：重试无意义，单次失败即返回。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(
        wechat_handler=wechat_kefu_handler(
            seen, lambda _n: httpx.Response(200, json={"errcode": 43004, "errmsg": "require subscribe"})
        )
    )

    resp = await client.post(
        "/internal/kefu/send",
        headers=SECRET,
        json={"messages": [{"openid": "oGONE", "content": "发不出去"}]},
    )

    result = resp.json()["results"][0]
    assert result["ok"] is False
    assert result["errcode"] == 43004
    assert seen["send"] == 1


async def test_45015_out_of_48h_window_reported_verbatim(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    """48 小时客服窗口已过（45015）：不重试、原样带回 errcode/errmsg 供后端话术层判断。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(
        wechat_handler=wechat_kefu_handler(
            seen, lambda _n: httpx.Response(200, json={"errcode": 45015, "errmsg": "reply is out of session time"})
        )
    )

    resp = await client.post(
        "/internal/kefu/send",
        headers=SECRET,
        json={"messages": [{"openid": "oLATE", "content": "太晚了"}]},
    )

    assert resp.status_code < 500
    result = resp.json()["results"][0]
    assert result["ok"] is False
    assert result["errcode"] == 45015
    assert "session time" in (result["errmsg"] or "")
    assert seen["send"] == 1


async def test_network_error_isolated_per_item(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    """微信侧 HTTP 5xx：该条按网络异常失败，同批其它条照发，端点整体仍 200。"""
    seen: dict[str, Any] = {}

    def behavior(n: int) -> httpx.Response:
        return httpx.Response(500, text="bad gateway") if n == 1 else httpx.Response(200, json={"errcode": 0})

    client = asgi_factory(wechat_handler=wechat_kefu_handler(seen, behavior))

    resp = await client.post(
        "/internal/kefu/send",
        headers=SECRET,
        json={"messages": [
            {"openid": "oBAD", "content": "第一条会炸"},
            {"openid": "oGOOD", "content": "第二条要成功"},
        ]},
    )

    assert resp.status_code == 200
    assert resp.json()["code"] == 0
    results = resp.json()["results"]
    assert results[0]["ok"] is False and "网络异常" in (results[0]["errmsg"] or "")
    assert results[1]["ok"] is True
    assert seen["send"] == 2


async def test_token_first_fetch_failure_is_chinese_and_never_5xx(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    """首次取 token 就失败：该条失败并带中文提示，端点绝不冒 500。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(
        wechat_handler=wechat_kefu_handler(
            seen,
            token_behavior=lambda _n: httpx.Response(200, json={"errcode": 40013, "errmsg": "invalid appid"}),
        )
    )

    resp = await client.post(
        "/internal/kefu/send",
        headers=SECRET,
        json={"messages": [{"openid": "oT", "content": "拿不到票"}]},
    )

    assert resp.status_code == 200
    assert resp.json()["code"] == 0
    result = resp.json()["results"][0]
    assert result["ok"] is False
    assert "access_token" in (result["errmsg"] or "")
    assert seen.get("send", 0) == 0


async def test_missing_or_wrong_secret_forbids_and_sends_nothing(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    """无密钥 / 密钥错误 → 403 + code 40300，且不产生任何出站调用。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(wechat_handler=wechat_kefu_handler(seen))

    no_secret = await client.post("/internal/kefu/send", json={"messages": []})
    wrong_secret = await client.post(
        "/internal/kefu/send", headers={"X-Internal-Secret": "nope"}, json={"messages": []}
    )

    assert no_secret.status_code == 403
    assert no_secret.json()["code"] == 40300
    assert wrong_secret.status_code == 403
    assert seen.get("send", 0) == 0
    assert seen.get("token", 0) == 0


async def test_malformed_body_returns_400_without_outbound(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    """请求体格式错 → 400 + code 40000；四种畸形形态都不得触达微信。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(wechat_handler=wechat_kefu_handler(seen))

    malformed: list[Any] = [
        {"wrong": "shape"},
        {"messages": "not-a-list"},
        {"messages": [{"openid": "o1"}]},
        {"messages": [{"openid": "  ", "content": "有空格"}]},
        {"messages": [{"openid": "o1", "content": 123}]},
    ]
    for payload in malformed:
        resp = await client.post("/internal/kefu/send", headers=SECRET, json=payload)
        assert resp.status_code == 400, payload
        assert resp.json()["code"] == 40000, payload

    assert seen.get("send", 0) == 0
    assert seen.get("token", 0) == 0


async def test_empty_messages_batch_is_zero_code_with_no_calls(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    """空批次：视为成功但零发送（后端话术层会按收件人过滤后传空列表）。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(wechat_handler=wechat_kefu_handler(seen))

    resp = await client.post("/internal/kefu/send", headers=SECRET, json={"messages": []})

    assert resp.status_code == 200
    assert resp.json() == {"code": 0, "results": []}
    assert seen.get("send", 0) == 0
