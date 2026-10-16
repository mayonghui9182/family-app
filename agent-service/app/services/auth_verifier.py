"""Step 1 身份校验：转发 X-Access-Token 调 backend GET /api/auth/me。

LoginResponse{accessToken, nickname, families[{id, role, roleName}]}；
家庭作用域不匹配 → 46000；孩子成员 → 40302；token 失效 → 40103。
失败立即短路，绝不调用 LLM。
"""
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

import httpx

from ..models.errors import AuthError, FamilyScopeError, RoleDeniedError
from ..settings import get_settings


@dataclass(frozen=True)
class CurrentUser:
    """校验后的当前用户（家庭作用域内）。"""

    nickname: str
    family_id: int
    role: str  # OWNER / ADULT


_ALLOWED_ROLES = ("OWNER", "ADULT")


class AuthVerifier:
    """backend /api/auth/me 校验器；client_factory 可注入（测试用 MockTransport）。"""

    def __init__(self, client_factory: Callable[[], httpx.AsyncClient] | None = None) -> None:
        self._client_factory = client_factory or self._default_factory

    @staticmethod
    def _default_factory() -> httpx.AsyncClient:
        return httpx.AsyncClient(base_url=get_settings().backend_base_url, timeout=10.0)

    async def verify(self, family_id: int, access_token: str) -> CurrentUser:
        """校验 token 并确认 family_id 属于该用户、角色为 OWNER/ADULT。"""
        try:
            async with self._client_factory() as client:
                resp = await client.get("/api/auth/me", headers={"X-Access-Token": access_token})
        except httpx.HTTPError as exc:
            raise AuthError() from exc
        if resp.status_code != 200:
            raise AuthError()
        payload: dict[str, Any] = resp.json()
        if payload.get("code") != 0 or not isinstance(payload.get("data"), dict):
            raise AuthError()
        data: dict[str, Any] = payload["data"]
        families = data.get("families") or []
        matched = next((f for f in families if f.get("id") == family_id), None)
        if matched is None:
            raise FamilyScopeError()
        role = str(matched.get("role", ""))
        if role not in _ALLOWED_ROLES:
            raise RoleDeniedError()
        return CurrentUser(nickname=str(data.get("nickname") or ""), family_id=family_id, role=role)
