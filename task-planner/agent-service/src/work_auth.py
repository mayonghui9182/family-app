"""工作服务可信认证：不接受未经验证的用户标识，日志不记录凭据。"""

from __future__ import annotations

import hmac
import re
from collections.abc import Mapping
from contextvars import ContextVar
from dataclasses import dataclass

import httpx
from starlette.responses import JSONResponse


class AuthenticationError(Exception):
    """身份无效或认证服务不可用。"""


@dataclass(frozen=True)
class WorkIdentity:
    """认证后的用户和原始调用凭据，仅用于内网传递。"""

    user_id: str
    headers: dict[str, str]


async def verify_identity(
    headers: Mapping[str, str],
    *,
    backend_url: str,
    internal_secret: str,
    transport: httpx.AsyncBaseTransport | None = None,
) -> WorkIdentity:
    """用户请求核验令牌；内部请求核验密钥后检查账号有效性。"""
    token = headers.get("x-access-token", "")
    scope = headers.get("x-work-user-id", "")
    supplied_secret = headers.get("x-internal-secret", "")
    if token:
        if not re.fullmatch(r"[0-9a-f]{64}", token):
            raise AuthenticationError("请重新登录")
        path = "/api/auth/me"
        forwarded = {"X-Access-Token": token}
        expected = None
    else:
        if (
            not internal_secret
            or not supplied_secret
            or not hmac.compare_digest(supplied_secret, internal_secret)
            or not re.fullmatch(r"[1-9][0-9]{0,18}", scope)
        ):
            raise AuthenticationError("请先登录")
        path = f"/api/internal/work-users/{scope}"
        forwarded = {"X-Internal-Secret": internal_secret}
        expected = scope
    try:
        async with httpx.AsyncClient(
            transport=transport, timeout=5.0, follow_redirects=False
        ) as client:
            response = await client.get(backend_url.rstrip("/") + path, headers=forwarded)
        if response.status_code != 200:
            raise AuthenticationError("身份验证失败")
        body = response.json()
        data = body.get("data")
        if body.get("code") != 0 or not isinstance(data, dict):
            raise AuthenticationError("身份验证失败")
        raw_id = data.get("userId")
        if isinstance(raw_id, bool) or not isinstance(raw_id, int) or raw_id <= 0:
            raise AuthenticationError("身份验证失败")
        user_id = str(raw_id)
        if expected is not None and expected != user_id:
            raise AuthenticationError("身份验证失败")
        if expected is not None:
            forwarded["X-Work-User-Id"] = user_id
        return WorkIdentity(user_id, forwarded)
    except (httpx.HTTPError, ValueError, TypeError, AttributeError) as exc:
        raise AuthenticationError("身份验证暂不可用") from exc

# 请求身份与凭据随异步任务继承，跨请求结束后恢复。

current_identity: ContextVar[WorkIdentity | None] = ContextVar("work_identity", default=None)


class WorkAuthMiddleware:
    """REST 与 MCP 统一在业务入口前认证；健康探针无业务数据。"""

    def __init__(self, app, *, backend_url: str, internal_secret: str, bind_owner=None):
        self.app = app
        self.backend_url = backend_url
        self.internal_secret = internal_secret
        self.bind_owner = bind_owner

    async def __call__(self, scope, receive, send):
        path = scope.get("path", "")
        if scope["type"] != "http" or not (path.startswith("/api/") or path == "/mcp"):
            await self.app(scope, receive, send)
            return
        headers = {k.decode("latin1").lower(): v.decode("latin1")
                   for k, v in scope.get("headers", [])}
        try:
            identity = await verify_identity(
                headers, backend_url=self.backend_url, internal_secret=self.internal_secret)
        except AuthenticationError:
            response = JSONResponse(
                {"code": 40101, "msg": "身份验证失败，请重新登录或稍后重试", "data": None},
                status_code=401)
            await response(scope, receive, send)
            return
        token = current_identity.set(identity)
        owner_token = self.bind_owner.set(identity.user_id) if self.bind_owner else None
        try:
            await self.app(scope, receive, send)
        finally:
            if self.bind_owner:
                self.bind_owner.reset(owner_token)
            current_identity.reset(token)
