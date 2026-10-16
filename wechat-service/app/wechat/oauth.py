"""微信网页授权（OAuth2 snsapi_base）：授权 URL 构造 + code 换 openid（design.md D3）。

snsapi_base 静默授权只拿 openid，不触达用户隐私字段；openid 仅在服务端流转。
"""
from urllib.parse import quote

import httpx

from ..settings import Settings


class WechatApiError(RuntimeError):
    """微信 API 返回错误或网络失败（调用方降级为中文提示页）。"""


# 免密登录后允许直达的站内深链前缀（design.md D3：新增路由须显式加白，否则静默回落首页）
NEXT_WHITELIST: frozenset[str] = frozenset({"/agent/draft/", "/tasks", "/calendar", "/dashboard", "/chat"})
NEXT_MAX_LENGTH = 200
# 白名单前缀之后允许的边界字符，防止 /tasksevil 这类「前缀粘连」蒙过 startswith
NEXT_BOUNDARY_CHARS: frozenset[str] = frozenset({"/", "?", "#"})


def sanitize_next(raw: str | None) -> str | None:
    """校验 next 深链：只放行白名单前缀的站内绝对路径，其余返回 None（回落首页，不报错）。

    逐条对应一种开放重定向绕过手法，缺一都可能被利用：
    - 协议相对 //evil.com 与 /\\evil.com（浏览器把反斜杠归一为正斜杠，等价协议相对）；
    - 带 scheme 的绝对地址（http:// 、javascript:）；
    - 含 .. 路径段（防从白名单前缀里穿越出去）；
    - 前缀粘连（/tasksevil 不是 /tasks 的子路径）；
    - 空白与控制字符（防 CRLF 响应拆分，也挡掉手输粘贴的脏值）；
    - 超长值（防拼接出畸形 URL）。
    """
    if not raw or len(raw) > NEXT_MAX_LENGTH:
        return None
    if any(ord(ch) <= 0x20 or ch == "\x7f" for ch in raw):
        return None
    if "\\" in raw:
        return None
    if not raw.startswith("/") or raw.startswith("//"):
        return None
    if ".." in raw.split("/"):
        return None
    for prefix in NEXT_WHITELIST:
        if not raw.startswith(prefix):
            continue
        rest = raw[len(prefix) :]
        if rest == "" or prefix.endswith("/") or rest[0] in NEXT_BOUNDARY_CHARS:
            return raw
    return None


def build_authorize_url(settings: Settings, state: str) -> str:
    """构造公众号网页授权跳转 URL（snsapi_base 静默授权）。

    redirect_uri 指向本服务 /wechat/oauth/callback（经 nginx /wechat/ 反代）。
    """
    app_id = settings.wechat_app_id.get_secret_value()
    redirect_uri = quote(f"{settings.oauth_callback_base}/wechat/oauth/callback", safe="")
    return (
        f"{settings.wechat_open_base}/connect/oauth2/authorize"
        f"?appid={app_id}&redirect_uri={redirect_uri}"
        f"&response_type=code&scope=snsapi_base&state={state}#wechat_redirect"
    )


async def exchange_code_for_openid(
    client: httpx.AsyncClient, settings: Settings, code: str
) -> str:
    """用授权 code 换 openid：GET sns/oauth2/access_token。

    成功返回 openid；微信返回 errcode 或网络异常 → WechatApiError（中文降级页）。
    """
    params = {
        "appid": settings.wechat_app_id.get_secret_value(),
        "secret": settings.wechat_app_secret.get_secret_value(),
        "code": code,
        "grant_type": "authorization_code",
    }
    try:
        resp = await client.get("/sns/oauth2/access_token", params=params)
        resp.raise_for_status()
        data = resp.json()
    except (httpx.HTTPError, ValueError) as exc:
        raise WechatApiError(f"微信授权接口调用失败：{exc}") from exc

    if "openid" not in data:
        errcode = data.get("errcode")
        errmsg = data.get("errmsg", "")
        raise WechatApiError(f"微信授权失败（errcode={errcode} {errmsg}），请稍后从菜单重试")
    return str(data["openid"])
