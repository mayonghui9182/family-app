"""客服消息文本下发（Change 14 D6）：cgi-bin/message/custom/send + 单条隔离。

职责边界：
- 本模块只负责「把一段文本发给一个 openid」，话术与深链拼装归 backend（Task 8）；
- token 失效（40001/42001）强制刷新后重试，且**只重试一次**（反复刷 token 会打爆日额度）；
- 其余非零 errcode（43004 未关注、45015 超出 48 小时客服窗口等）重试无意义，原样带回
  errcode/errmsg 让后端话术层按码降级；
- 传输异常一律转成失败结果，绝不向批量端点抛出 ⇒ 端点永不 5xx。

为什么不用模板消息：客服消息在用户与公众号交互后 48 小时内免申请模板即可下发，
正好覆盖「用户刚发消息 → 回推拆解结果」的场景；代价是超窗即 45015，必须把码透出来。
"""
from dataclasses import dataclass
from typing import Any

import httpx

from .oauth import WechatApiError
from .token_manager import WechatTokenManager

CUSTOM_SEND_PATH = "/cgi-bin/message/custom/send"

# access_token 失效类错误码：刷新后重试一次
_TOKEN_INVALID_ERRCODES = {40001, 42001}


@dataclass
class KefuResult:
    """单条客服消息发送结果（批量端点逐条返回）。"""

    openid: str
    ok: bool
    errcode: int | None = None
    errmsg: str | None = None


async def _post_send(
    client: httpx.AsyncClient,
    token: str,
    openid: str,
    content: str,
) -> dict[str, Any]:
    payload = {"touser": openid, "msgtype": "text", "text": {"content": content}}
    resp = await client.post(CUSTOM_SEND_PATH, params={"access_token": token}, json=payload)
    resp.raise_for_status()
    result: dict[str, Any] = resp.json()
    return result


async def send_text(
    client: httpx.AsyncClient,
    manager: WechatTokenManager,
    openid: str,
    content: str,
) -> KefuResult:
    """发送单条客服文本；单条级失败都以 KefuResult 返回（token 首次获取失败抛 WechatApiError，由端点兜住）。"""
    token = await manager.get_token()

    try:
        result = await _post_send(client, token, openid, content)
    except (httpx.HTTPError, ValueError) as exc:
        return KefuResult(openid=openid, ok=False, errmsg=f"网络异常：{exc}")

    errcode = int(result.get("errcode", 0))
    if errcode == 0:
        return KefuResult(openid=openid, ok=True)

    if errcode in _TOKEN_INVALID_ERRCODES:
        # token 失效：强制刷新后重试一次，仍失败则带着第二次的码返回
        try:
            new_token = await manager.refresh()
            result = await _post_send(client, new_token, openid, content)
            errcode = int(result.get("errcode", 0))
            if errcode == 0:
                return KefuResult(openid=openid, ok=True)
        except (httpx.HTTPError, ValueError, WechatApiError) as exc:
            return KefuResult(openid=openid, ok=False, errmsg=f"token 刷新后重试失败：{exc}")

    return KefuResult(
        openid=openid, ok=False, errcode=errcode,
        errmsg=str(result.get("errmsg") or "微信返回错误"),
    )
