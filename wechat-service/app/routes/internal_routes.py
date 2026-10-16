"""内部运维端点：菜单创建 + 模板消息批量发送 + 客服消息批量下发（X-Internal-Secret 守护）。"""
import hmac
from dataclasses import asdict
from typing import Any

import httpx
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from ..settings import Settings
from ..wechat.kefu import KefuResult, send_text
from ..wechat.menu import create_menu
from ..wechat.oauth import WechatApiError
from ..wechat.template import SendResult, send_template_message
from ..wechat.token_manager import WechatTokenManager

router = APIRouter()


def _check_secret(request: Request) -> JSONResponse | None:
    """密钥校验，失败返回 403 JSONResponse；通过返回 None。"""
    settings: Settings = request.app.state.settings
    provided = request.headers.get("X-Internal-Secret", "")
    expected = settings.internal_api_secret.get_secret_value()
    if not expected or not hmac.compare_digest(provided, expected):
        return JSONResponse({"code": 40300, "msg": "内部密钥缺失或不正确"}, status_code=403)
    return None


@router.post("/internal/setup-menu")
async def setup_menu(request: Request) -> JSONResponse:
    """创建公众号菜单。无密钥/密钥错误 → 403；微信侧失败 → 502 中文信息。"""
    denied = _check_secret(request)
    if denied is not None:
        return denied

    settings: Settings = request.app.state.settings
    wechat_client: httpx.AsyncClient = request.app.state.wechat_client
    manager = WechatTokenManager(wechat_client, settings)
    try:
        await create_menu(wechat_client, manager, settings)
    except WechatApiError as exc:
        return JSONResponse({"code": 50201, "msg": str(exc)}, status_code=502)
    return JSONResponse({"code": 0, "msg": "菜单创建成功"})


@router.post("/internal/template/send")
async def template_send(request: Request) -> JSONResponse:
    """批量模板消息发送（Change 12）。

    body: {"messages": [{"openid": str, "event": str, "params": {...}}]}
    逐条隔离返回 {"code":0, "results":[{openid, ok, skipped?, errcode?, errmsg?}]}；
    单条任何失败都不影响同批其它消息，端点整体不返回 5xx。
    """
    denied = _check_secret(request)
    if denied is not None:
        return denied

    try:
        body = await request.json()
        messages = body["messages"]
        if not isinstance(messages, list):
            raise TypeError("messages 必须是数组")
        for msg in messages:
            if not isinstance(msg.get("openid"), str) or not isinstance(msg.get("event"), str):
                raise TypeError("每条消息需含字符串 openid 与 event")
    except (ValueError, TypeError, KeyError, AttributeError):
        return JSONResponse(
            {"code": 40000, "msg": "请求体格式错误，应为 {messages:[{openid,event,params?}]}"},
            status_code=400,
        )

    settings: Settings = request.app.state.settings
    wechat_client: httpx.AsyncClient = request.app.state.wechat_client
    # 同批共享一个 token manager（一次取 token，批量复用）
    manager = WechatTokenManager(wechat_client, settings)

    results: list[dict[str, Any]] = []
    for msg in messages:
        openid = msg["openid"]
        event = msg["event"]
        params = msg.get("params") or {}
        try:
            result = await send_template_message(
                wechat_client, manager, settings, openid, event, params
            )
        except WechatApiError as exc:
            # token 首次获取失败等致命错误：该条失败，不影响其它条
            result = SendResult(openid=openid, ok=False, errmsg=str(exc))
        except Exception as exc:  # noqa: BLE001 - 单条隔离铁律，绝不让批量端点 500
            result = SendResult(openid=openid, ok=False, errmsg=f"内部错误：{exc}")
        results.append(asdict(result))

    return JSONResponse({"code": 0, "results": results})


@router.post("/internal/kefu/send")
async def kefu_send(request: Request) -> JSONResponse:
    """批量客服消息文本下发（Change 14 D6：拆解完成后把草稿深链推回私聊）。

    body: {"messages": [{"openid": str, "content": str}]}
    逐条隔离返回 {"code":0, "results":[{openid, ok, errcode?, errmsg?}]}；
    单条任何失败（含 token 首次获取失败、微信 5xx）都不影响同批其它消息，端点整体不返回 5xx。
    """
    denied = _check_secret(request)
    if denied is not None:
        return denied

    try:
        body = await request.json()
        messages = body["messages"]
        if not isinstance(messages, list):
            raise TypeError("messages 必须是数组")
        for msg in messages:
            openid = msg.get("openid")
            content = msg.get("content")
            if not isinstance(openid, str) or not openid.strip():
                raise TypeError("每条消息需含非空字符串 openid")
            if not isinstance(content, str) or not content.strip():
                raise TypeError("每条消息需含非空字符串 content")
    except (ValueError, TypeError, KeyError, AttributeError):
        return JSONResponse(
            {"code": 40000, "msg": "请求体格式错误，应为 {messages:[{openid,content}]}"},
            status_code=400,
        )

    settings: Settings = request.app.state.settings
    wechat_client: httpx.AsyncClient = request.app.state.wechat_client
    # 同批共享一个 token manager（一次取 token，批量复用，省微信额度）
    manager = WechatTokenManager(wechat_client, settings)

    results: list[dict[str, Any]] = []
    for msg in messages:
        openid = msg["openid"]
        try:
            result = await send_text(wechat_client, manager, openid, msg["content"])
        except WechatApiError as exc:
            # token 首次获取失败等致命错误：该条失败，不影响其它条
            result = KefuResult(openid=openid, ok=False, errmsg=str(exc))
        except Exception as exc:  # noqa: BLE001 - 单条隔离铁律，绝不让批量端点 500
            result = KefuResult(openid=openid, ok=False, errmsg=f"内部错误：{exc}")
        results.append(asdict(result))

    return JSONResponse({"code": 0, "results": results})
