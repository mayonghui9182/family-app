"""公众号自定义菜单创建（design.md D1，手写协议无 SDK）。

菜单两个 view 按钮：家庭应用 / 微信绑定（OAuth 入口）。
Change 11 只建菜单；菜单点击事件推送处理在后续 Change（模板消息）接入。

注意：微信一级菜单 name 上限 4 个汉字（16 字节），超长报 errcode=40018。
"""
import httpx

from ..settings import Settings
from .oauth import WechatApiError
from .token_manager import WechatTokenManager


def build_menu_payload(settings: Settings) -> dict[str, object]:
    """菜单 JSON：两个 view 类型按钮，URL 指向公网回调基址下的 H5/授权入口。"""
    base = settings.oauth_callback_base
    return {
        "button": [
            {
                "type": "view",
                "name": "家庭应用",
                "url": f"{base}/",
            },
            {
                "type": "view",
                "name": "微信绑定",
                "url": f"{base}/wechat/authorize",
            },
        ]
    }


async def create_menu(
    client: httpx.AsyncClient, manager: WechatTokenManager, settings: Settings
) -> bool:
    """先取 access_token（单飞缓存）再调 cgi-bin/menu/create；errcode=0 成功。"""
    token = await manager.get_token()
    try:
        resp = await client.post(
            "/cgi-bin/menu/create",
            params={"access_token": token},
            json=build_menu_payload(settings),
        )
        resp.raise_for_status()
        data = resp.json()
    except (httpx.HTTPError, ValueError) as exc:
        raise WechatApiError(f"菜单创建请求失败：{exc}") from exc

    if data.get("errcode") != 0:
        raise WechatApiError(
            f"菜单创建被微信拒绝：errcode={data.get('errcode')} {data.get('errmsg', '')}"
        )
    return True
