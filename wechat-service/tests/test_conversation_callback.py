"""新版微信公众号持续会话入口：可靠落库确认、重试语义与账号隔离。"""

import json
import xml.etree.ElementTree as ET
from collections.abc import Callable
from datetime import UTC, datetime
from typing import Any

import httpx
import pytest

from app.connectors.backend_client import CONVERSATION_ACCEPT_PATH, CONVERSATION_ACCEPT_TIMEOUT
from app.wechat.signature import calc_signature

from .conftest import Handler, make_settings

TOKEN = "callback-token"
TS = "1774598400"
NONCE = "nonce-conversation"
OPENID = "oUserOPENID001"
ACCOUNT = "gh_familyapp"
MSG_ID = "778812345"
EXPECTED_RECEIVED_AT = datetime.fromtimestamp(int(TS), tz=UTC).isoformat().replace("+00:00", "Z")


def callback_query(signature: str | None = None) -> dict[str, str]:
    """构造微信回调查询参数。"""
    actual = signature if signature is not None else calc_signature(TOKEN, TS, NONCE)
    return {"signature": actual, "timestamp": TS, "nonce": NONCE}


def inbound_xml(*, content: str = "周六带爸去复查", account: str = ACCOUNT) -> str:
    """构造微信公众号明文文本消息。"""
    return (
        "<xml>"
        f"<ToUserName><![CDATA[{account}]]></ToUserName>"
        f"<FromUserName><![CDATA[{OPENID}]]></FromUserName>"
        f"<CreateTime>{TS}</CreateTime>"
        "<MsgType><![CDATA[text]]></MsgType>"
        f"<Content><![CDATA[{content}]]></Content>"
        f"<MsgId>{MSG_ID}</MsgId>"
        "</xml>"
    )


def conversation_settings() -> Any:
    """只开启新版持续会话入口。"""
    return make_settings(wechat_inbound_enabled=False, wechat_conversation_enabled=True)


def success_handler(seen: dict[str, Any], *, reply_type: str = "RECEIVED") -> Handler:
    """记录完整请求并返回可靠落库确认。"""

    def handler(request: httpx.Request) -> httpx.Response:
        seen["calls"] = seen.get("calls", 0) + 1
        seen["path"] = request.url.path
        seen["secret"] = request.headers.get("X-Internal-Secret", "")
        seen["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "code": 0,
                "msg": "ok",
                "data": {
                    "message_id": 91,
                    "persisted": True,
                    "duplicate": False,
                    "reply_type": reply_type,
                },
            },
        )

    return handler


def reply_content(text: str) -> str:
    """读取微信被动回复正文。"""
    root = ET.fromstring(text)
    node = root.find("Content")
    return "" if node is None else (node.text or "")


async def test_complete_message_is_persisted_before_callback_returns_200(
    asgi_factory: Callable[..., httpx.AsyncClient],
) -> None:
    """长正文不截断，ToUserName 作为公众号账号维度一并传给 backend。"""
    seen: dict[str, Any] = {}
    content = "请帮我安排：" + "每周复查和取药，提醒爸爸带病历。" * 30
    client = asgi_factory(backend_handler=success_handler(seen), settings=conversation_settings())

    response = await client.post("/wechat/callback", params=callback_query(), content=inbound_xml(content=content))

    assert response.status_code == 200
    assert seen["calls"] == 1
    assert seen["path"] == CONVERSATION_ACCEPT_PATH
    assert seen["secret"] == "itest-secret"
    assert seen["body"] == {
        "wechat_account_id": ACCOUNT,
        "openid": OPENID,
        "msg_id": MSG_ID,
        "content": content,
        "received_at": EXPECTED_RECEIVED_AT,
    }
    assert reply_content(response.text) == "收到，我正在整理。"


async def test_invalid_signature_never_calls_persistence(
    asgi_factory: Callable[..., httpx.AsyncClient],
) -> None:
    """验签失败在解析和落库之前返回。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(backend_handler=success_handler(seen), settings=conversation_settings())

    response = await client.post(
        "/wechat/callback",
        params=callback_query(signature="bad-signature"),
        content=inbound_xml(),
    )

    assert response.status_code == 403
    assert seen.get("calls", 0) == 0


@pytest.mark.parametrize("failure", ["http_500", "timeout"])
async def test_transport_failure_returns_retryable_503(
    asgi_factory: Callable[..., httpx.AsyncClient],
    failure: str,
) -> None:
    """backend 失败或超过 2.5 秒预算时返回 5xx，让微信发起重试。"""

    def handler(request: httpx.Request) -> httpx.Response:
        if failure == "timeout":
            raise httpx.ReadTimeout("read timed out", request=request)
        return httpx.Response(500, json={"code": 50000, "msg": "failed"})

    client = asgi_factory(backend_handler=handler, settings=conversation_settings())

    response = await client.post("/wechat/callback", params=callback_query(), content=inbound_xml())

    assert response.status_code == 503
    assert response.text == ""


@pytest.mark.parametrize(
    "body",
    [
        {"code": 50001, "msg": "failed", "data": None},
        {
            "code": 0,
            "msg": "ok",
            "data": {"message_id": 1, "persisted": False, "duplicate": False, "reply_type": "RECEIVED"},
        },
        {
            "code": 0,
            "msg": "ok",
            "data": {"persisted": True, "duplicate": False, "reply_type": "RECEIVED"},
        },
        {
            "code": 0,
            "msg": "ok",
            "data": {"message_id": 1, "persisted": True, "duplicate": False, "reply_type": "UNKNOWN"},
        },
    ],
)
async def test_invalid_persistence_confirmation_returns_503(
    asgi_factory: Callable[..., httpx.AsyncClient],
    body: dict[str, Any],
) -> None:
    """HTTP 200 但业务失败或确认字段不完整，也不能向微信确认成功。"""

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=body)

    client = asgi_factory(backend_handler=handler, settings=conversation_settings())

    response = await client.post("/wechat/callback", params=callback_query(), content=inbound_xml())

    assert response.status_code == 503


async def test_duplicate_delivery_is_acknowledged_without_second_processing(
    asgi_factory: Callable[..., httpx.AsyncClient],
) -> None:
    """微信重复投递两次，backend 的幂等结果使两次都成功确认但只处理一次。"""
    seen = {"calls": 0, "processed": 0}

    def handler(_request: httpx.Request) -> httpx.Response:
        seen["calls"] += 1
        duplicate = seen["calls"] > 1
        if not duplicate:
            seen["processed"] += 1
        return httpx.Response(
            200,
            json={
                "code": 0,
                "msg": "ok",
                "data": {
                    "message_id": 91,
                    "persisted": True,
                    "duplicate": duplicate,
                    "reply_type": "RECEIVED",
                },
            },
        )

    client = asgi_factory(backend_handler=handler, settings=conversation_settings())

    first = await client.post("/wechat/callback", params=callback_query(), content=inbound_xml())
    second = await client.post("/wechat/callback", params=callback_query(), content=inbound_xml())

    assert first.status_code == 200
    assert second.status_code == 200
    assert seen == {"calls": 2, "processed": 1}


async def test_unbound_reply_gives_clear_next_step(
    asgi_factory: Callable[..., httpx.AsyncClient],
) -> None:
    """未绑定消息已经落库后，回复明确的绑定入口指引。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(
        backend_handler=success_handler(seen, reply_type="BIND_REQUIRED"),
        settings=conversation_settings(),
    )

    response = await client.post("/wechat/callback", params=callback_query(), content=inbound_xml())

    assert response.status_code == 200
    assert "绑定家庭账号" in reply_content(response.text)


async def test_missing_wechat_account_returns_503_without_backend_call(
    asgi_factory: Callable[..., httpx.AsyncClient],
) -> None:
    """缺少 ToUserName 时无法建立账号级幂等键，必须等待微信重试。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(backend_handler=success_handler(seen), settings=conversation_settings())

    response = await client.post(
        "/wechat/callback",
        params=callback_query(),
        content=inbound_xml(account=""),
    )

    assert response.status_code == 503
    assert seen.get("calls", 0) == 0


def test_conversation_backend_timeout_budget_is_at_most_2_5_seconds() -> None:
    """为微信 5 秒被动回复时限预留网络和 XML 响应时间。"""
    assert CONVERSATION_ACCEPT_TIMEOUT <= 2.5


@pytest.mark.parametrize("hint", ["上次已成功创建 2 项任务，可在家庭任务中查看。", None, "", "x" * 601])
async def test_undelivered_result_hint_uses_passive_reply_only_when_valid(asgi_factory, hint) -> None:  # type: ignore[no-untyped-def]
    """仅转发后端已授权的有限长度提示，不触发客服外呼或第二次确认。"""
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["calls"] = seen.get("calls", 0) + 1
        assert request.url.path == CONVERSATION_ACCEPT_PATH
        return httpx.Response(200, json={"code": 0, "data": {
            "message_id": 91, "persisted": True, "duplicate": False,
            "reply_type": "RESULT_AVAILABLE", "reply_text": hint,
        }})

    client = asgi_factory(backend_handler=handler, settings=conversation_settings())
    response = await client.post("/wechat/callback", params=callback_query(), content=inbound_xml())
    if isinstance(hint, str) and 0 < len(hint) <= 600:
        assert response.status_code == 200
        assert reply_content(response.text) == hint
    else:
        assert response.status_code == 503
    assert seen["calls"] == 1
