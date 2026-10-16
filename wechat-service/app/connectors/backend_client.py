"""backend 内部 API 客户端（design.md D2）：wechat-service 无 DB，绑定数据全在 backend。

信封：backend 统一 Result{code,msg,data}；X-Internal-Secret 头由 backend
InternalSecretInterceptor 恒定时间校验（test profile 放行、生产强制）。
约定业务码：40115 = WECHAT_NOT_BOUND（HTTP 200 流程态）。
"""

from dataclasses import dataclass
from typing import Any

import httpx

from ..settings import Settings, get_settings

WECHAT_NOT_BOUND_CODE = 40115
INBOUND_ACCEPT_PATH = "/api/internal/wechat/inbound-message"
# 受理端点只做幂等落库（毫秒级）；3 秒是 fail-fast 上限，给微信 5 秒被动回复硬时限留余量
INBOUND_ACCEPT_TIMEOUT = 3.0
CONVERSATION_ACCEPT_PATH = "/api/internal/wechat/conversation/messages"
# 新会话入口必须在微信回调时限内确认落库，超时由微信重试保证最终送达
CONVERSATION_ACCEPT_TIMEOUT = 2.5


@dataclass(frozen=True)
class InboundVerdict:
    """受理裁决：accepted=True 已入队；否则 reason 携带六值 snake_case 拒绝原因（Task 2 契约）。"""

    accepted: bool
    reason: str | None


@dataclass(frozen=True)
class ConversationAcceptance:
    """会话消息落库结果；只有 persisted=True 才允许微信回调返回 200。"""

    message_id: int
    persisted: bool
    duplicate: bool
    reply_type: str
    reply_text: str | None = None


class BackendPersistenceError(RuntimeError):
    """backend 未能确认消息可靠落库。"""


class BackendClient:
    """httpx.AsyncClient 封装 Change 11 三个内部端点。"""

    def __init__(self, client: httpx.AsyncClient | None = None, settings: Settings | None = None) -> None:
        self._settings = settings or get_settings()
        self._secret = self._settings.internal_api_secret.get_secret_value()
        # 密钥头在每次请求显式携带：注入的测试 client 与自建 client 行为一致
        self._client = client or httpx.AsyncClient(
            base_url=self._settings.backend_base_url,
            timeout=10.0,
        )

    def _headers(self) -> dict[str, str]:
        return {"X-Internal-Secret": self._secret}

    async def _post(self, path: str, payload: dict[str, Any], timeout: float | None = None) -> dict[str, Any]:
        # httpx 显式传 timeout=None 的含义是「不限时」，故只有需要覆写时才带参
        if timeout is None:
            resp = await self._client.post(path, json=payload, headers=self._headers())
        else:
            resp = await self._client.post(path, json=payload, headers=self._headers(), timeout=timeout)
        # 403（密钥不对/缺失）等 HTTP 错误直接透传抛出
        resp.raise_for_status()
        body: dict[str, Any] = resp.json()
        return body

    async def _get(self, path: str, params: dict[str, Any]) -> dict[str, Any]:
        resp = await self._client.get(path, params=params, headers=self._headers())
        resp.raise_for_status()
        body: dict[str, Any] = resp.json()
        return body

    async def get_bind(self, openid: str) -> dict[str, Any] | None:
        """查绑定：命中返回 data（bound/userId），未绑定（40115）返回 None。"""
        body = await self._get("/api/internal/wechat/bind", {"openid": openid})
        if body.get("code") == WECHAT_NOT_BOUND_CODE:
            return None
        return body.get("data")

    async def mint_ticket(self, openid: str) -> str:
        """为未绑定 openid 签发一次性绑定票据。"""
        body = await self._post("/api/internal/wechat/ticket", {"openid": openid})
        data = body.get("data") or {}
        return str(data["ticket"])

    async def login_by_openid(self, openid: str) -> dict[str, Any] | None:
        """已绑定 openid 免密换登录态：成功返回 LoginResponse data，未绑定返回 None。"""
        body = await self._post("/api/internal/wechat/login", {"openid": openid})
        if body.get("code") == WECHAT_NOT_BOUND_CODE:
            return None
        return body.get("data")

    async def accept_inbound_message(
        self, *, openid: str, msg_id: str, content: str, received_at: str
    ) -> InboundVerdict:
        """转交入站文本消息做幂等受理（Change 14 Task 2 契约）；传输异常向上抛，由回调侧统一收口空串。"""
        body = await self._post(
            INBOUND_ACCEPT_PATH,
            {"openid": openid, "msg_id": msg_id, "content": content, "received_at": received_at},
            timeout=INBOUND_ACCEPT_TIMEOUT,
        )
        data: dict[str, Any] = body.get("data") or {}
        return InboundVerdict(accepted=bool(data.get("accepted")), reason=data.get("reason"))

    async def accept_conversation_message(
        self,
        *,
        wechat_account_id: str,
        openid: str,
        msg_id: str,
        content: str,
        received_at: str,
    ) -> ConversationAcceptance:
        """把完整文本交给新会话入口，并严格校验 backend 的落库确认。"""
        body = await self._post(
            CONVERSATION_ACCEPT_PATH,
            {
                "wechat_account_id": wechat_account_id,
                "openid": openid,
                "msg_id": msg_id,
                "content": content,
                "received_at": received_at,
            },
            timeout=CONVERSATION_ACCEPT_TIMEOUT,
        )
        if body.get("code") != 0:
            raise BackendPersistenceError("backend 返回业务失败")

        data = body.get("data")
        if not isinstance(data, dict):
            raise BackendPersistenceError("backend 未返回落库结果")
        message_id = data.get("message_id")
        reply_type = data.get("reply_type")
        reply_text = data.get("reply_text")
        if (
            data.get("persisted") is not True
            or not isinstance(message_id, int)
            or isinstance(message_id, bool)
            or message_id <= 0
            or reply_type not in {"RECEIVED", "BIND_REQUIRED", "RESULT_AVAILABLE"}
            or (reply_type == "RESULT_AVAILABLE" and (
                not isinstance(reply_text, str) or not reply_text.strip() or len(reply_text) > 600
            ))
        ):
            raise BackendPersistenceError("backend 落库确认无效")

        return ConversationAcceptance(
            message_id=message_id,
            persisted=True,
            duplicate=data.get("duplicate") is True,
            reply_type=reply_type,
            reply_text=reply_text if reply_type == "RESULT_AVAILABLE" else None,
        )

    async def aclose(self) -> None:
        await self._client.aclose()
