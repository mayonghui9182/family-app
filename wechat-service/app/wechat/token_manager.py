"""公众号 access_token 缓存管理器（design.md D4）。

策略：
- 进程内存缓存 token + 过期时间点；剩余有效期 < 300s 时主动续期；
- asyncio.Lock 单飞：并发取 token 只发起一次 HTTP（微信 quota 2000 次/日，省着用）；
- 刷新失败但旧 token 仍在：保留旧值，退避 60s 后才允许下次重试（不把故障放大成雪崩）；
- 首次获取失败（无旧值）：抛 WechatApiError。
"""
import asyncio
import time
from dataclasses import dataclass

import httpx

from ..settings import Settings
from .oauth import WechatApiError

REFRESH_LEEWAY_SECONDS = 300
BACKOFF_SECONDS = 60


@dataclass
class _CacheEntry:
    token: str
    expires_at: float  # time.monotonic() 口径


class WechatTokenManager:
    """access_token 单飞缓存。httpx client 与 settings 可注入（测试全离线 mock）。"""

    def __init__(self, client: httpx.AsyncClient, settings: Settings) -> None:
        self._client = client
        self._settings = settings
        self._lock = asyncio.Lock()
        self._entry: _CacheEntry | None = None
        self._next_retry_at: float = 0.0

    async def get_token(self) -> str:
        """取有效 access_token；缓存命中直接返回，否则单飞刷新。"""
        entry = self._entry
        if entry is not None and entry.expires_at - time.monotonic() > REFRESH_LEEWAY_SECONDS:
            if time.monotonic() < self._next_retry_at:
                return entry.token
            # 临近过期：进入单飞续期
        async with self._lock:
            # double-check：等锁期间可能已被其他协程刷新
            entry = self._entry
            now = time.monotonic()
            if entry is not None and entry.expires_at - now > REFRESH_LEEWAY_SECONDS:
                return entry.token
            if entry is not None and now < self._next_retry_at:
                return entry.token
            return await self._refresh_locked()

    async def refresh(self) -> str:
        """强制刷新（Change 12：模板消息遇 40001/42001 后重试一次用）。"""
        async with self._lock:
            return await self._refresh_locked()

    async def _refresh_locked(self) -> str:
        """调用 cgi-bin/token 刷新（调用方持锁）。"""
        params = {
            "grant_type": "client_credential",
            "appid": self._settings.wechat_app_id.get_secret_value(),
            "secret": self._settings.wechat_app_secret.get_secret_value(),
        }
        try:
            resp = await self._client.get("/cgi-bin/token", params=params)
            resp.raise_for_status()
            data = resp.json()
        except (httpx.HTTPError, ValueError) as exc:
            return self._handle_failure(f"网络异常：{exc}")

        if "access_token" not in data:
            return self._handle_failure(
                f"errcode={data.get('errcode')} {data.get('errmsg', '')}"
            )

        token = str(data["access_token"])
        expires_in = int(data.get("expires_in", 7200))
        self._entry = _CacheEntry(
            token=token,
            expires_at=time.monotonic() + expires_in,
        )
        self._next_retry_at = 0.0
        return token

    def _handle_failure(self, detail: str) -> str:
        """刷新失败：有旧 token 则保留并退避；无旧 token 则抛出。"""
        if self._entry is not None:
            self._next_retry_at = time.monotonic() + BACKOFF_SECONDS
            return self._entry.token
        raise WechatApiError(f"获取微信 access_token 失败（{detail}），请稍后重试")
