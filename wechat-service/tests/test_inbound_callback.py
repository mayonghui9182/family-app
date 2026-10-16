"""入站回调真实受理（Change 14 Task 9 / spec wechat-integration 第 1 条 Requirement）。

铁律来源：微信被动回复 5 秒上限 + 推送失败会重推 ⇒
- 验签不过 403 且不解析 body（省 CPU 且不给解析器喂不可信数据）；
- backend 不可达/超时一律 200 + 空串（绝不让微信看到 5xx，否则重推风暴）；
- 事件与非文本不转交 backend；XML 解析必须防 XXE（拒 DOCTYPE/ENTITY）。
"""

import hashlib
import json
import time
import xml.etree.ElementTree as ET
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from datetime import UTC, datetime
from typing import Any

import httpx
import pytest
from loguru import logger

from app.wechat.signature import calc_signature

from .conftest import Handler, make_settings

TOKEN = "callback-token"
TS = "1774598400"
NONCE = "nonce-task9"
OPENID = "oUserOPENID001"  # 用户 openid（推送方向的 FromUserName）
ACCOUNT = "gh_familyapp"  # 公众号原始 ID（推送方向的 ToUserName）
MSG_ID = "778812345"
CONTENT = "周六带爸去复查"
ACCEPT_PATH = "/api/internal/wechat/inbound-message"
# Instant.parse 要求 ISO-8601 带 Z：CreateTime(unix 秒) 的期望换算值
EXPECTED_RECEIVED_AT = datetime.fromtimestamp(int(TS), tz=UTC).isoformat().replace("+00:00", "Z")


def callback_query(signature: str | None = None) -> dict[str, str]:
    """构造带合法签名的查询参数；传 signature 可覆盖成坏签名。"""
    sig = signature if signature is not None else calc_signature(TOKEN, TS, NONCE)
    return {"signature": sig, "timestamp": TS, "nonce": NONCE}


def inbound_xml(
    msg_type: str = "text",
    content: str = CONTENT,
    msg_id: str | None = MSG_ID,
    event: str | None = None,
    create_time: str = TS,
) -> str:
    """按微信明文推送格式造 body（字符串字段一律 CDATA，与真实推送一致）。"""
    parts = [
        "<xml>",
        f"<ToUserName><![CDATA[{ACCOUNT}]]></ToUserName>",
        f"<FromUserName><![CDATA[{OPENID}]]></FromUserName>",
        f"<CreateTime>{create_time}</CreateTime>",
        f"<MsgType><![CDATA[{msg_type}]]></MsgType>",
    ]
    if msg_type.lower() == "text":
        parts.append(f"<Content><![CDATA[{content}]]></Content>")
    if msg_id is not None:
        parts.append(f"<MsgId>{msg_id}</MsgId>")
    if event is not None:
        parts.append(f"<Event><![CDATA[{event}]]></Event>")
    parts.append("</xml>")
    return "".join(parts)


def accept_handler(seen: dict[str, Any], data: dict[str, Any] | None = None) -> Handler:
    """记录 backend 出站调用；data 可定制 Verdict（默认受理成功）。"""
    payload = data if data is not None else {"accepted": True, "reason": None}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["calls"] = seen.get("calls", 0) + 1
        seen["path"] = request.url.path
        seen["secret"] = request.headers.get("X-Internal-Secret", "")
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"code": 0, "msg": "ok", "data": payload})

    return handler


def reply_parts(text: str) -> dict[str, str]:
    """解析被动回复 XML 为 {字段名: 值}。"""
    root = ET.fromstring(text)
    return {child.tag: (child.text or "") for child in list(root)}


@contextmanager
def log_sink(level: str) -> Iterator[list[str]]:
    """loguru 不吃 pytest caplog，按官方方式临时挂 sink 收集日志文本。"""
    records: list[str] = []

    def sink(message: object) -> None:
        records.append(str(message))

    sink_id = logger.add(sink, level=level)
    try:
        yield records
    finally:
        logger.remove(sink_id)


def enabled_settings() -> Any:
    """入站受理开关打开的 Settings（用例主干几乎都要求开关开）。"""
    return make_settings(wechat_inbound_enabled=True)


async def test_text_message_forwarded_once_and_replies_receipt(asgi_factory: Callable[..., httpx.AsyncClient]) -> None:
    """用例①：合法 text → backend 恰好 1 次、透传四字段、被动回复「收到，正在理解…」，且收发方互换。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(backend_handler=accept_handler(seen), settings=enabled_settings())

    resp = await client.post("/wechat/callback", params=callback_query(), content=inbound_xml())

    assert resp.status_code == 200
    assert seen["calls"] == 1
    assert seen["path"] == ACCEPT_PATH
    assert seen["secret"] == "itest-secret"
    assert seen["body"] == {"openid": OPENID, "msg_id": MSG_ID, "content": CONTENT, "received_at": EXPECTED_RECEIVED_AT}
    parts = reply_parts(resp.text)
    assert parts["MsgType"] == "text"
    # 被动回复是「公众号 → 用户」方向，故 To/From 与推送相反（互换）
    assert parts["ToUserName"] == OPENID
    assert parts["FromUserName"] == ACCOUNT
    assert parts["Content"].startswith("收到，正在理解")


async def test_event_push_not_forwarded_returns_empty_and_logged(
    asgi_factory: Callable[..., httpx.AsyncClient],
) -> None:
    """用例②：event（订阅）→ 不调 backend、回空串、日志记录事件类型。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(backend_handler=accept_handler(seen), settings=enabled_settings())

    with log_sink("INFO") as records:
        resp = await client.post(
            "/wechat/callback",
            params=callback_query(),
            content=inbound_xml(msg_type="event", msg_id=None, event="subscribe"),
        )

    assert resp.status_code == 200
    assert resp.text == ""
    assert seen.get("calls", 0) == 0
    combined_logs = "".join(records)
    assert "subscribe" in combined_logs
    assert OPENID not in combined_logs
    assert hashlib.sha256(OPENID.encode("utf-8")).hexdigest()[:16] in combined_logs


@pytest.mark.parametrize("msg_type", ["image", "voice"])
async def test_non_text_replies_text_only_hint(asgi_factory: Callable[..., httpx.AsyncClient], msg_type: str) -> None:
    """用例③：图片/语音不转交，回「目前我只能看懂文字，请直接打字发给我」（spec 逐字）。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(backend_handler=accept_handler(seen), settings=enabled_settings())

    resp = await client.post("/wechat/callback", params=callback_query(), content=inbound_xml(msg_type=msg_type))

    assert resp.status_code == 200
    assert seen.get("calls", 0) == 0
    assert reply_parts(resp.text)["Content"] == "目前我只能看懂文字，请直接打字发给我"


@pytest.mark.parametrize(
    ("msg_type", "event", "forwarded"),
    [("TEXT", None, True), ("Text", None, True), ("EVENT", "subscribe", False)],
)
async def test_msgtype_case_folding_decides_the_branch(
    asgi_factory: Callable[..., httpx.AsyncClient], msg_type: str, event: str | None, forwarded: bool
) -> None:
    """大小写归一自增（M12 变异反证产物）：MsgType 分支判定必须不依赖第三方报的大小写。

    反证依据：去掉 inbound.py 里 MsgType 的 .lower() 后，全部用例仍绿——因为既有载荷一律小写。
    但那是真实缺陷：路由用字符串相等判分支，若微信推成大写 TEXT，消息会被误判成「非文本」而只回
    「只能看懂文字」，用户的任务被静默丢弃（老人场景硬伤）。故此处用大写载荷判别「谁在归一」。
    """
    seen: dict[str, Any] = {}
    client = asgi_factory(backend_handler=accept_handler(seen), settings=enabled_settings())

    resp = await client.post(
        "/wechat/callback",
        params=callback_query(),
        content=inbound_xml(msg_type=msg_type, msg_id=None if event else MSG_ID, event=event),
    )

    assert resp.status_code == 200
    assert seen.get("calls", 0) == (1 if forwarded else 0)
    if forwarded:
        assert seen["body"]["content"] == CONTENT
        assert reply_parts(resp.text)["Content"].startswith("收到，正在理解")
    else:
        assert resp.text == ""


async def test_invalid_signature_returns_403_without_parsing_body(
    asgi_factory: Callable[..., httpx.AsyncClient],
) -> None:
    """用例④：验签失败 → 403 且不解析 body（body 故意给非 XML，证明根本没进解析器）。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(backend_handler=accept_handler(seen), settings=enabled_settings())

    resp = await client.post(
        "/wechat/callback",
        params=callback_query(signature="deadbeef"),
        content="this-is-not-xml",
    )

    assert resp.status_code == 403
    assert seen.get("calls", 0) == 0


async def test_backend_unreachable_returns_empty_and_logs_error(asgi_factory: Callable[..., httpx.AsyncClient]) -> None:
    """用例⑤：backend 连接被拒 → 200 + 空串 + error 日志（绝不 5xx，防重推风暴）。"""

    def handler(_request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused")

    client = asgi_factory(backend_handler=handler, settings=enabled_settings())

    with log_sink("ERROR") as records:
        resp = await client.post("/wechat/callback", params=callback_query(), content=inbound_xml())

    assert resp.status_code == 200
    assert resp.text == ""
    assert len(records) >= 1


async def test_backend_timeout_returns_empty_and_logs_error(asgi_factory: Callable[..., httpx.AsyncClient]) -> None:
    """超时与不可达同收口：ReadTimeout → 200 + 空串 + error 日志。"""

    def handler(_request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("read timed out")

    client = asgi_factory(backend_handler=handler, settings=enabled_settings())

    with log_sink("ERROR") as records:
        resp = await client.post("/wechat/callback", params=callback_query(), content=inbound_xml())

    assert resp.status_code == 200
    assert resp.text == ""
    assert len(records) >= 1


@pytest.mark.parametrize(
    ("reason", "expected_in", "expected_empty"),
    [
        ("not_bound", "绑定微信", False),
        ("no_family", "加入任何家庭", False),
        ("role_denied", "成年人", False),
        ("multi_family", "请打开应用选好家庭后再用 AI 拆解", False),
        ("intake_disabled", "暂时关闭", False),
        ("duplicate", "", True),
        ("bogus_unknown_reason", "", True),
    ],
)
async def test_verdict_reason_selects_chinese_reply(
    asgi_factory: Callable[..., httpx.AsyncClient], reason: str, expected_in: str, expected_empty: bool
) -> None:
    """用例⑥⑦ + 收口：六种 reason 按 design D3 话术表回复；duplicate/未知一律空串（宁默不许诺）。"""
    seen: dict[str, Any] = {}
    data = {"accepted": False, "reason": reason}
    client = asgi_factory(backend_handler=accept_handler(seen, data), settings=enabled_settings())

    resp = await client.post("/wechat/callback", params=callback_query(), content=inbound_xml())

    assert resp.status_code == 200
    assert seen["calls"] == 1
    if expected_empty:
        assert resp.text == ""
    else:
        assert expected_in in reply_parts(resp.text)["Content"]


async def test_chain_completes_within_five_seconds(asgi_factory: Callable[..., httpx.AsyncClient]) -> None:
    """用例⑧：整条同步链墙钟 < 5 秒（微信被动回复硬上限，spec SHALL）。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(backend_handler=accept_handler(seen), settings=enabled_settings())

    started = time.perf_counter()
    resp = await client.post("/wechat/callback", params=callback_query(), content=inbound_xml())
    elapsed = time.perf_counter() - started

    assert resp.status_code == 200
    assert elapsed < 5.0


@pytest.mark.parametrize(
    "xxe_body",
    [
        '<!DOCTYPE xml [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>'
        "<xml><ToUserName>gh</ToUserName><FromUserName>oU</FromUserName>"
        "<MsgType>text</MsgType><Content>&xxe;</Content><MsgId>1</MsgId><CreateTime>1</CreateTime></xml>",
        '<!DOCTYPE xml [<!entity xxe SYSTEM "http://evil.test/x.dtd">]>'
        "<xml><ToUserName>gh</ToUserName><FromUserName>oU</FromUserName>"
        "<MsgType>text</MsgType><Content>hi</Content><MsgId>2</MsgId><CreateTime>2</CreateTime></xml>",
        # 判别式载荷：大写声明 + 正文不引用实体 ⇒ 这是**合法** XML，xml.etree 解析得过去。
        # 前两个变体抽掉字节防线后仍会被解析器挡下（未定义实体 / 小写标记非法），只有这条
        # 能证明拒收来自 FORBIDDEN_MARKERS 防线本身。
        '<!DOCTYPE xml [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>'
        "<xml><ToUserName>gh</ToUserName><FromUserName>oU</FromUserName>"
        "<MsgType>text</MsgType><Content>hi</Content><MsgId>3</MsgId><CreateTime>3</CreateTime></xml>",
    ],
)
async def test_xxe_payload_is_rejected_without_forwarding(
    asgi_factory: Callable[..., httpx.AsyncClient], xxe_body: str
) -> None:
    """防线自增：含 DOCTYPE/ENTITY（大小写不敏感）的 XML 拒解析 → 200 + 空串 + 不调 backend。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(backend_handler=accept_handler(seen), settings=enabled_settings())

    resp = await client.post("/wechat/callback", params=callback_query(), content=xxe_body)

    assert resp.status_code == 200
    assert resp.text == ""
    assert seen.get("calls", 0) == 0


@pytest.mark.parametrize(
    "bad_body",
    [
        "<xml><MsgType>text</MsgType><Content>断裂的 XML",
    ],
)
async def test_unparsable_body_returns_empty_and_logs_error(
    asgi_factory: Callable[..., httpx.AsyncClient], bad_body: str
) -> None:
    """防线自增：语法断裂报文 → 解析器抛错 → 200 + 空串 + ERROR 日志 + 不调 backend（绝不 5xx）。

    超长但合法的报文不在本用例覆盖：截断报文会被解析器判为语法错，从而替尺寸防线挡刀，
    钉不住 MAX_BODY_BYTES ⇒ 由 test_oversized_but_wellformed_body_is_rejected_by_size_guard 判别。
    """
    seen: dict[str, Any] = {}
    client = asgi_factory(backend_handler=accept_handler(seen), settings=enabled_settings())

    with log_sink("ERROR") as records:
        resp = await client.post("/wechat/callback", params=callback_query(), content=bad_body)

    assert resp.status_code == 200
    assert resp.text == ""
    assert seen.get("calls", 0) == 0
    assert any("解析失败" in r for r in records)


async def test_oversized_but_wellformed_body_is_rejected_by_size_guard(
    asgi_factory: Callable[..., httpx.AsyncClient],
) -> None:
    """尺寸防线自增：>64KB 但 XML 完全合法的报文 ⇒ 只有 MAX_BODY_BYTES 能拦（判别式用例）。

    反证依据：把上限抬到 10**9 后原有用例仍全绿——超长报文若同时被截断，解析器的语法错
    会替尺寸防线挡刀。故这里用「合法且超长」的报文，并断言防线自身的日志文案完成归因。
    """
    seen: dict[str, Any] = {}
    client = asgi_factory(backend_handler=accept_handler(seen), settings=enabled_settings())
    huge = inbound_xml(content="填" * 40000)

    with log_sink("ERROR") as records:
        resp = await client.post("/wechat/callback", params=callback_query(), content=huge)

    assert resp.status_code == 200
    assert resp.text == ""
    assert seen.get("calls", 0) == 0
    assert any("字节上限" in r for r in records)


async def test_inbound_disabled_short_circuits_after_signature_ok(
    asgi_factory: Callable[..., httpx.AsyncClient],
) -> None:
    """双开关自增：wechat_inbound_enabled=False（默认关）→ 验签通过后直接空串，不解析不调 backend。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(backend_handler=accept_handler(seen), settings=make_settings())

    resp = await client.post("/wechat/callback", params=callback_query(), content=inbound_xml())

    assert resp.status_code == 200
    assert resp.text == ""
    assert seen.get("calls", 0) == 0


async def test_missing_msgid_is_not_forwarded(asgi_factory: Callable[..., httpx.AsyncClient]) -> None:
    """防线自增：text 缺 MsgId 不转发（backend 对缺失 msg_id 回 400，客户端先挡，省一次坏请求）。"""
    seen: dict[str, Any] = {}
    client = asgi_factory(backend_handler=accept_handler(seen), settings=enabled_settings())

    resp = await client.post("/wechat/callback", params=callback_query(), content=inbound_xml(msg_id=None))

    assert resp.status_code == 200
    assert resp.text == ""
    assert seen.get("calls", 0) == 0


async def test_dirty_createtime_falls_back_to_now_without_losing_message(
    asgi_factory: Callable[..., httpx.AsyncClient],
) -> None:
    """脏值自增（M11 变异反证产物）：CreateTime 非数字 ⇒ 不 5xx、不丢消息，received_at 退化为当前时间。

    去掉 create_raw.isdigit() 守卫后 int("not-a-number") 抛 ValueError，而路由只 catch
    InboundParseError ⇒ 直接 500，违反「除验签 403 外一律 200」的收口铁律。
    """
    seen: dict[str, Any] = {}
    client = asgi_factory(backend_handler=accept_handler(seen), settings=enabled_settings())

    resp = await client.post(
        "/wechat/callback", params=callback_query(), content=inbound_xml(create_time="not-a-number")
    )

    assert resp.status_code == 200
    assert seen["calls"] == 1
    assert reply_parts(resp.text)["Content"].startswith("收到，正在理解")
    received_at = seen["body"]["received_at"]
    assert received_at.endswith("Z")
    assert received_at != EXPECTED_RECEIVED_AT
    parsed = datetime.fromisoformat(received_at.replace("Z", "+00:00"))
    assert abs((datetime.now(tz=UTC) - parsed).total_seconds()) < 60


def test_backend_accept_timeout_is_fail_fast() -> None:
    """fail-fast 自增：受理调用超时常量存在且 ≤ 3 秒（design D2：nginx 30s ≠ 微信 5s，留给微信侧余量）。"""
    from app.connectors import backend_client as backend_client_module

    timeout = getattr(backend_client_module, "INBOUND_ACCEPT_TIMEOUT", None)
    assert timeout is not None, "缺少入站受理超时常量"
    assert float(timeout) <= 3.0


def test_dangerous_markers_are_rejected_by_guard_not_parser() -> None:
    """归因自增：合法但危险的 XML 必须由字节防线拦下，而不是靠解析器抛语法错。

    反证依据：把 FORBIDDEN_MARKERS 清空后，原有 XXE 用例仍全绿——解析器替防线挡了刀，
    断言被歪打正着地满足。故此处直接对「谁挡的」归因：错误信息必须来自防线文案。
    """
    from app.wechat.inbound import InboundParseError, parse_inbound_message

    legal_but_dangerous = (
        b'<!DOCTYPE xml [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>'
        b"<xml><MsgType>text</MsgType><Content>hi</Content></xml>"
    )

    with pytest.raises(InboundParseError) as guard:
        parse_inbound_message(legal_but_dangerous)
    assert "DOCTYPE" in str(guard.value)

    with pytest.raises(InboundParseError) as syntax:
        parse_inbound_message(b"<xml><MsgType>text")
    assert "解析失败" in str(syntax.value)
