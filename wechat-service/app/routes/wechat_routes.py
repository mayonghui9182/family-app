"""微信公众号回调与 OAuth 分流路由（design.md D3）。

分流：
- 已绑定 openid → backend 免密换 token → 302 H5，token 只在 URL fragment（不进日志/Referer）；
- 未绑定 → backend 签发一次性票据 → 302 H5 绑定页 ?ticket=（openid 永不到前端）。
"""

import hashlib
import secrets
import time
from urllib.parse import quote

import httpx
from fastapi import APIRouter, Request
from fastapi.responses import PlainTextResponse, RedirectResponse, Response
from loguru import logger

from ..connectors.backend_client import BackendClient, BackendPersistenceError
from ..settings import Settings
from ..wechat.inbound import (
    TEXT_ONLY_HINT,
    InboundMessage,
    InboundParseError,
    build_text_reply,
    parse_inbound_message,
    reply_text_for_verdict,
    to_received_at,
)
from ..wechat.oauth import WechatApiError, build_authorize_url, exchange_code_for_openid, sanitize_next
from ..wechat.signature import verify_signature

router = APIRouter()

STATE_TTL_SECONDS = 600
CONVERSATION_RECEIVED_REPLY = "收到，我正在整理。"
CONVERSATION_BIND_REPLY = "消息已经收到。请先从公众号菜单绑定家庭账号，再继续创建任务。"


class OAuthStateStore:
    """OAuth state 进程内存暂存（防 CSRF）：随机 state → (过期时间点, next 深链)，单次消费。"""

    def __init__(self, ttl_seconds: int = STATE_TTL_SECONDS) -> None:
        self._ttl = ttl_seconds
        self._pending: dict[str, tuple[float, str | None]] = {}

    def mint(self, next_path: str | None = None) -> str:
        """签发 state；next_path 必须是已过白名单校验的站内深链（或 None）。"""
        self._prune()
        state = secrets.token_urlsafe(16)
        self._pending[state] = (time.monotonic() + self._ttl, next_path)
        return state

    def consume(self, state: str) -> tuple[bool, str | None]:
        """消费 state，返回 (是否有效, next 深链)。调用方必须解包判断首项，勿用整体真值。"""
        self._prune()
        entry = self._pending.pop(state, None)
        if entry is None:
            return False, None
        expires_at, next_path = entry
        return expires_at >= time.monotonic(), next_path

    def _prune(self) -> None:
        now = time.monotonic()
        expired = [s for s, (expires_at, _) in self._pending.items() if expires_at < now]
        for s in expired:
            self._pending.pop(s, None)


def chinese_error_page(message: str, status_code: int) -> Response:
    """微信 API 故障降级页：纯中文大字提示，老人可读。"""
    html = (
        "<html><head><meta charset='utf-8'>"
        "<meta name='viewport' content='width=device-width,initial-scale=1'>"
        "<title>温馨提示</title></head>"
        "<body style='font-size:20px;line-height:1.8;padding:24px;text-align:center'>"
        f"<p>😵 微信服务暂时开小差了</p><p>{message}</p>"
        "<p>请关闭页面，从公众号菜单重新进入</p></body></html>"
    )
    return Response(content=html, media_type="text/html; charset=utf-8", status_code=status_code)


@router.get("/wechat/callback")
async def wechat_verify(
    request: Request,
    signature: str = "",
    timestamp: str = "",
    nonce: str = "",
    echostr: str = "",
) -> Response:
    """公众号服务器配置验签（GET）：通过则原样返回 echostr 纯文本。"""
    settings: Settings = request.app.state.settings
    token = settings.wechat_token.get_secret_value()
    if not verify_signature(token, timestamp, nonce, signature):
        return PlainTextResponse("forbidden", status_code=403)
    return PlainTextResponse(echostr)


def _hash_openid(openid: str) -> str:
    """openid 只以稳定短哈希进入日志，禁止输出完整标识。"""
    return hashlib.sha256(openid.encode("utf-8")).hexdigest()[:16]


def _text_reply(msg: InboundMessage, content: str) -> Response:
    """把被动回复 XML 包成文本响应（微信明文模式）。"""
    return Response(content=build_text_reply(msg, content), media_type="text/plain; charset=utf-8")


@router.post("/wechat/callback")
async def wechat_message(
    request: Request,
    signature: str = "",
    timestamp: str = "",
    nonce: str = "",
) -> Response:
    """公众号消息/事件推送受理。

    旧模式继续使用原受理裁决；新会话模式只有 backend 确认落库才返回 200，
    超时或落库失败返回 503，由微信重试保证最终送达。
    """
    settings: Settings = request.app.state.settings
    token = settings.wechat_token.get_secret_value()
    if not verify_signature(token, timestamp, nonce, signature):
        return PlainTextResponse("forbidden", status_code=403)
    if not settings.wechat_inbound_enabled and not settings.wechat_conversation_enabled:
        return PlainTextResponse("")

    raw = await request.body()
    try:
        msg = parse_inbound_message(raw)
    except InboundParseError as exc:
        logger.error("微信入站消息解析失败 error={}", exc)
        return PlainTextResponse("")

    if msg.msg_type == "event":
        logger.info(
            "微信事件推送（不受理）event={} openid_hash={}",
            msg.event,
            _hash_openid(msg.from_user_name),
        )
        return PlainTextResponse("")
    if msg.msg_type != "text":
        return _text_reply(msg, TEXT_ONLY_HINT)
    if not msg.msg_id or not msg.from_user_name:
        logger.error("微信文本消息缺少 MsgId/FromUserName，不转发 msg_id={}", msg.msg_id)
        return PlainTextResponse("")

    backend: BackendClient = request.app.state.backend
    if settings.wechat_conversation_enabled:
        if not msg.to_user_name:
            logger.error("微信会话消息缺少 ToUserName，等待微信重试 msg_id={}", msg.msg_id)
            return PlainTextResponse("", status_code=503)
        try:
            acceptance = await backend.accept_conversation_message(
                wechat_account_id=msg.to_user_name,
                openid=msg.from_user_name,
                msg_id=msg.msg_id,
                content=msg.content or "",
                received_at=to_received_at(msg.create_time),
            )
        except (httpx.HTTPError, BackendPersistenceError) as exc:
            logger.error("微信会话消息落库确认失败 msg_id={} error_type={}", msg.msg_id, type(exc).__name__)
            return PlainTextResponse("", status_code=503)

        conversation_reply = (
            CONVERSATION_BIND_REPLY if acceptance.reply_type == "BIND_REQUIRED" else CONVERSATION_RECEIVED_REPLY
        )
        if acceptance.reply_type == "RESULT_AVAILABLE" and acceptance.reply_text:
            conversation_reply = acceptance.reply_text
        return _text_reply(msg, conversation_reply)

    try:
        verdict = await backend.accept_inbound_message(
            openid=msg.from_user_name,
            msg_id=msg.msg_id,
            content=msg.content or "",
            received_at=to_received_at(msg.create_time),
        )
    except httpx.HTTPError as exc:
        logger.error("微信入站消息转交 backend 受理失败 error={}", exc)
        return PlainTextResponse("")

    reply = reply_text_for_verdict(verdict.accepted, verdict.reason)
    if reply is None:
        return PlainTextResponse("")
    return _text_reply(msg, reply)


@router.get("/wechat/authorize")
async def wechat_authorize(request: Request) -> Response:
    """菜单入口：生成随机 state（记入白名单内的 next 深链）并 302 到微信授权页（snsapi_base 静默）。"""
    settings: Settings = request.app.state.settings
    store: OAuthStateStore = request.app.state.oauth_states
    # 直接读 query 而不声明 next 形参：参数名 next 会遮蔽内置函数
    next_path = sanitize_next(request.query_params.get("next"))
    state = store.mint(next_path)
    return RedirectResponse(build_authorize_url(settings, state), status_code=302)


@router.get("/wechat/oauth/callback")
async def wechat_oauth_callback(request: Request, code: str = "", state: str = "") -> Response:
    """微信授权回调：code 换 openid → backend 查绑定 → 302 分流。"""
    settings: Settings = request.app.state.settings
    store: OAuthStateStore = request.app.state.oauth_states
    backend: BackendClient = request.app.state.backend
    wechat_client: httpx.AsyncClient = request.app.state.wechat_client

    state_ok, next_path = store.consume(state)
    if not code or not state_ok:
        return chinese_error_page("授权链接已过期，请从公众号菜单重新进入", 400)

    try:
        openid = await exchange_code_for_openid(wechat_client, settings, code)
        login = await backend.login_by_openid(openid)
    except WechatApiError as exc:
        return chinese_error_page(str(exc), 502)
    except httpx.HTTPError as exc:
        return chinese_error_page(f"家庭应用服务暂时不可用：{exc}", 502)

    h5_base = settings.h5_base_url.rstrip("/")
    # next 已在签发 state 前过白名单，这里只负责编码：保留 / 便于前端直读，转义 & = # 防破坏结构
    next_suffix = "" if next_path is None else f"&next={quote(next_path, safe='/')}"
    if login is not None:
        # 已绑定：token 仅放 URL fragment（#后不发服务器、不进 Referer/访问日志）
        token = quote(str(login.get("accessToken", "")))
        return RedirectResponse(f"{h5_base}/wechat/callback#token={token}{next_suffix}", status_code=302)

    # 未绑定：一次性票据引导绑定页（openid 不离开服务端），next 随绑定页 query 透传
    try:
        ticket = await backend.mint_ticket(openid)
    except httpx.HTTPError as exc:
        return chinese_error_page(f"家庭应用服务暂时不可用：{exc}", 502)
    return RedirectResponse(f"{h5_base}/wechat/bind?ticket={quote(ticket)}{next_suffix}", status_code=302)
