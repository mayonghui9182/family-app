"""OAuth `next` 深链 + 开放重定向白名单（change14 Task 10）。

风险面：/wechat/authorize 是免密登录入口，授权完成后 302 会在 URL fragment 里带上
家庭应用 token。若 `next` 不做校验，攻击者只要诱导老人点「公众号菜单 → 授权」，
就能让 token 落到外部站点（开放重定向）。
因此 next 必须收敛到站内白名单；非法值静默丢弃（回落首页）而不是报错，
避免老人看到无意义的错误页。
"""
from urllib.parse import parse_qs, urlparse

import httpx
import pytest

from app.routes.wechat_routes import OAuthStateStore
from app.wechat.oauth import NEXT_WHITELIST, sanitize_next

from .test_oauth import backend_handler, state_from, wechat_oauth_handler

H5 = "https://tunnel.example.com"
# 与改造前逐字一致的落地地址（回归护栏 + 白名单拒绝时的回落地址）
BOUND_NO_NEXT = f"{H5}/wechat/callback#token=fam-token-abc"
UNBOUND_NO_NEXT = f"{H5}/wechat/bind?ticket=TICKET123"

# 合法深链：白名单前缀 + 允许带 query / 子路径
SAFE_NEXTS = [
    "/agent/draft/9",
    "/tasks",
    "/calendar",
    "/dashboard",
    "/chat",
    "/tasks?filter=active",
    "/agent/draft/9#draft",
    "/chat/room-1",
]

# 必须被丢弃的 next：每一条都只能由白名单拦下（其余机制会放行）
DANGEROUS_NEXTS = [
    "//evil.com/x",  # 协议相对地址：浏览器补成 https://evil.com
    "/\\/evil.com",  # 反斜杠：浏览器把 \ 归一为 /，等价协议相对
    "http://evil.com",
    "https://evil.com/path",
    "https://evil.com/tasks",  # 尾巴长得像白名单：改成 in 匹配就会被骗，只有前缀校验能拦
    "javascript:alert(1)",
    "/../etc/passwd",
    "/agent/draft/../../etc/passwd",  # 白名单前缀 + 路径穿越
    "/tasks/../../../etc",
    "/unknown/route",  # 站内路径但不在白名单（只有前缀校验能拦）
    "/tasksevil",  # 前缀粘连：无边界检查的 startswith 会误放行
    "/chatty",
    # 以下两条白名单前缀出现在路径中间：startswith 会拒，改成 in 匹配就放行
    "/evil/agent/draft/9",
    "/evil/chat/room-1",
    " /tasks",  # 前导空白
    "",
    # 以下三条都能穿过「白名单前缀」这道关，只能被各自那一条形状规则拦下
    # （变异反证用：删掉对应规则，这三条必须变绿 → 测试必须变红）
    "/agent/draft/9\r\nSet-Cookie: a=b",  # CRLF 响应拆分 → 控制字符规则
    "/agent/draft/" + "a" * 300,  # 超长值 → 长度上限规则
    "/agent/draft/9/\\/evil.com",  # 前缀之后的反斜杠 → 禁反斜杠规则
]


async def authorize_and_callback(
    client: httpx.AsyncClient, next_value: str | None, mode: str = "bound"
) -> httpx.Response:
    """走完整链路：/wechat/authorize（可带 next）→ 取 state → /wechat/oauth/callback。"""
    params: dict[str, str] = {} if next_value is None else {"next": next_value}
    location = (await client.get("/wechat/authorize", params=params)).headers["location"]
    code = {"bound": "wx-code-bound", "unbound": "wx-code-unbound"}[mode]
    return await client.get("/wechat/oauth/callback", params={"code": code, "state": state_from(location)})


def test_whitelist_constant_is_exactly_the_five_routes() -> None:
    """白名单常量钉死为 design.md 约定的 5 个前缀，不得随意增删。"""
    assert set(NEXT_WHITELIST) == {"/agent/draft/", "/tasks", "/calendar", "/dashboard", "/chat"}


@pytest.mark.parametrize("value", SAFE_NEXTS)
def test_sanitize_next_accepts_whitelisted_paths(value: str) -> None:
    assert sanitize_next(value) == value


@pytest.mark.parametrize("value", [None, *DANGEROUS_NEXTS, "/tasks\ttab", "/tasks" + "a" * 200])
def test_sanitize_next_rejects_every_non_whitelisted_value(value: str | None) -> None:
    assert sanitize_next(value) is None


def test_state_store_carries_next_and_stays_single_use() -> None:
    """state → (过期时间, next)：消费成功要能取回 next，且第二次消费必须失败。"""
    store = OAuthStateStore()

    state = store.mint("/agent/draft/9")
    assert store.consume(state) == (True, "/agent/draft/9")
    assert store.consume(state) == (False, None)

    bare = store.mint()
    assert store.consume(bare) == (True, None)
    assert store.consume("forged-state") == (False, None)


async def test_authorize_keeps_next_server_side_only(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    """next 只存进服务端 state，绝不写进发给微信的跳转 URL（防篡改、防泄露隐私）。"""
    client = asgi_factory()

    location = (await client.get("/wechat/authorize", params={"next": "/agent/draft/9"})).headers["location"]

    assert location.startswith("https://open.weixin.test/connect/oauth2/authorize")
    assert state_from(location)
    assert "/agent/draft" not in location


async def test_bound_openid_lands_h5_with_next_in_fragment(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    """①合法 next → 302 落地 {h5}/wechat/callback#token=…&next=/agent/draft/9。"""
    client = asgi_factory(backend_handler=backend_handler("bound"), wechat_handler=wechat_oauth_handler())

    resp = await authorize_and_callback(client, "/agent/draft/9")

    assert resp.status_code == 302
    assert resp.headers["location"] == f"{BOUND_NO_NEXT}&next=/agent/draft/9"


@pytest.mark.parametrize("value", DANGEROUS_NEXTS)
async def test_dangerous_next_is_dropped_without_breaking_login(asgi_factory, value: str) -> None:  # type: ignore[no-untyped-def]
    """②白名单拒绝 → next 被丢弃、仍然正常 302 落首页（与不带 next 逐字一致），不报错。"""
    client = asgi_factory(backend_handler=backend_handler("bound"), wechat_handler=wechat_oauth_handler())

    resp = await authorize_and_callback(client, value)

    assert resp.status_code == 302
    location = resp.headers["location"]
    assert location == BOUND_NO_NEXT
    assert "evil.com" not in location
    assert "next=" not in location


async def test_login_without_next_is_byte_identical_to_before(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    """③未带 next → 已绑定/未绑定两条分支落地地址与现状逐字一致（回归护栏）。"""
    bound = asgi_factory(backend_handler=backend_handler("bound"), wechat_handler=wechat_oauth_handler())
    unbound = asgi_factory(backend_handler=backend_handler("unbound"), wechat_handler=wechat_oauth_handler())

    assert (await authorize_and_callback(bound, None, "bound")).headers["location"] == BOUND_NO_NEXT
    assert (await authorize_and_callback(unbound, None, "unbound")).headers["location"] == UNBOUND_NO_NEXT


async def test_unbound_bind_page_forwards_next_for_post_bind_redirect(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    """④未绑定分支同样透传 next（绑定完成后回原目标页），next 以 query 形式携带。"""
    client = asgi_factory(backend_handler=backend_handler("unbound"), wechat_handler=wechat_oauth_handler())

    resp = await authorize_and_callback(client, "/agent/draft/9", "unbound")

    assert resp.status_code == 302
    assert resp.headers["location"] == f"{UNBOUND_NO_NEXT}&next=/agent/draft/9"


@pytest.mark.parametrize("value", ["//evil.com", "http://evil.com", "/unknown/route"])
async def test_unbound_dangerous_next_falls_back_to_plain_bind_page(asgi_factory, value: str) -> None:  # type: ignore[no-untyped-def]
    client = asgi_factory(backend_handler=backend_handler("unbound"), wechat_handler=wechat_oauth_handler())

    resp = await authorize_and_callback(client, value, "unbound")

    assert resp.status_code == 302
    assert resp.headers["location"] == UNBOUND_NO_NEXT


async def test_next_cannot_smuggle_extra_fragment_params(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    """next 里的 & = # 必须被编码，否则会污染 fragment 结构、覆盖掉 token。"""
    tricky = "/tasks?filter=a&token=attacker"
    client = asgi_factory(backend_handler=backend_handler("bound"), wechat_handler=wechat_oauth_handler())

    location = (await authorize_and_callback(client, tricky)).headers["location"]

    fragment = parse_qs(urlparse(location).fragment)
    assert sorted(fragment) == ["next", "token"]
    assert fragment["token"] == ["fam-token-abc"]
    assert fragment["next"] == [tricky]


async def test_state_with_next_is_single_use(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    """带 next 的 state 同样只能消费一次，重放仍被拒。"""
    client = asgi_factory(backend_handler=backend_handler("bound"), wechat_handler=wechat_oauth_handler())
    location = (await client.get("/wechat/authorize", params={"next": "/agent/draft/9"})).headers["location"]
    state = state_from(location)

    first = await client.get("/wechat/oauth/callback", params={"code": "wx-code-1", "state": state})
    second = await client.get("/wechat/oauth/callback", params={"code": "wx-code-1", "state": state})

    assert first.status_code == 302
    assert second.status_code == 400
