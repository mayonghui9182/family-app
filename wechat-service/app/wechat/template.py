"""模板消息发送（Change 12）：事件渲染 + cgi-bin/message/template/send。

职责（design D1/D3/D4）：
- event → (template_id, 跳转 url, 中文 data 字段) 渲染；模板 id 未配置 → skipped 降级；
- token 失效（40001/42001）强制刷新后重试一次；用户取关（43004/43010）不重试；
- 传输异常单条隔离返回 ok=False，不抛到批量端点之外。
"""
from dataclasses import dataclass
from typing import Any

import httpx

from ..settings import Settings
from .oauth import WechatApiError
from .token_manager import WechatTokenManager

SEND_PATH = "/cgi-bin/message/template/send"

# access_token 失效类错误码：刷新后重试一次
_TOKEN_INVALID_ERRCODES = {40001, 42001}
# 用户未关注/已取关：重试无意义，直接失败
_UNREACHABLE_ERRCODES = {43004, 43010}


@dataclass
class SendResult:
    """单条发送结果（批量端点逐条返回）。"""

    openid: str
    ok: bool
    skipped: bool = False
    errcode: int | None = None
    errmsg: str | None = None


def _field(value: str) -> dict[str, str]:
    return {"value": value}


def render_template(
    settings: Settings, event: str, params: dict[str, Any]
) -> tuple[str, str, dict[str, object]] | None:
    """按 event 渲染 (template_id, url, data)。

    模板 id 未配置返回 None（调用方按 skipped 降级）；未知 event 抛 ValueError。
    """
    h5 = settings.h5_base_url.rstrip("/")

    if event == "task_digest":
        tpl = settings.wechat_tpl_task_digest_id
        if not tpl:
            return None
        data: dict[str, object] = {
            "first": _field("今日家庭任务早报"),
            "keyword1": _field(f"今日到期 {int(params.get('today_due', 0))} 件"),
            "keyword2": _field(f"逾期 {int(params.get('overdue', 0))} 件"),
            "remark": _field("点击查看任务详情"),
        }
        return tpl, f"{h5}/tasks", data

    if event == "summary_ready":
        tpl = settings.wechat_tpl_summary_id
        if not tpl:
            return None
        data = {
            "first": _field("今日家庭总结已生成"),
            "keyword1": _field(str(params.get("family_name", "我的家庭"))),
            "keyword2": _field(str(params.get("date", ""))),
            "remark": _field("点击查看总结面板"),
        }
        return tpl, f"{h5}/dashboard", data

    if event == "task_assigned":
        tpl = settings.wechat_tpl_task_assign_id
        if not tpl:
            return None
        due = params.get("due_date")
        data = {
            "first": _field("你有新的家庭任务"),
            "keyword1": _field(str(params.get("title", "新任务"))),
            "keyword2": _field(str(due) if due else "无截止日期"),
            "remark": _field("点击处理任务"),
        }
        return tpl, f"{h5}/tasks", data

    if event == "event_digest":
        tpl = settings.wechat_tpl_event_digest_id
        if not tpl:
            return None
        summary = str(params.get("event_summary", ""))
        # 长度兜底职责边界：后端只按条数（5 条）截断摘要，不限字符；
        # 渲染层负责字符数截断（超 40 取前 39 + 省略号，微信 keyword 位安全长度）
        if len(summary) > 40:
            summary = summary[:39] + "…"
        data = {
            "first": _field("今日家庭日程早报"),
            "keyword1": _field(f"今日 {int(params.get('event_count', 0))} 件安排"),
            "keyword2": _field(summary),
            "remark": _field("点击查看今日日程"),
        }
        # Change 13 Task 8：前端 /calendar 路由已上线，日程早报直达日程页（不再落首页让用户自己找）
        return tpl, f"{h5}/calendar", data

    raise ValueError(f"未知模板事件类型: {event}")


async def _post_send(
    client: httpx.AsyncClient,
    token: str,
    openid: str,
    template_id: str,
    url: str,
    data: dict[str, object],
) -> dict[str, Any]:
    payload = {"touser": openid, "template_id": template_id, "url": url, "data": data}
    resp = await client.post(SEND_PATH, params={"access_token": token}, json=payload)
    resp.raise_for_status()
    result: dict[str, Any] = resp.json()
    return result


async def send_template_message(
    client: httpx.AsyncClient,
    manager: WechatTokenManager,
    settings: Settings,
    openid: str,
    event: str,
    params: dict[str, Any],
) -> SendResult:
    """发送单条模板消息；任何单条级失败都以 SendResult 返回（token 首次获取失败抛 WechatApiError）。"""
    try:
        rendered = render_template(settings, event, params)
    except ValueError as exc:
        return SendResult(openid=openid, ok=False, errmsg=str(exc))
    if rendered is None:
        return SendResult(openid=openid, ok=False, skipped=True, errmsg="template_not_configured")

    template_id, url, data = rendered
    token = await manager.get_token()

    try:
        result = await _post_send(client, token, openid, template_id, url, data)
    except (httpx.HTTPError, ValueError) as exc:
        return SendResult(openid=openid, ok=False, errmsg=f"网络异常：{exc}")

    errcode = int(result.get("errcode", 0))
    if errcode == 0:
        return SendResult(openid=openid, ok=True)

    if errcode in _TOKEN_INVALID_ERRCODES:
        # token 失效：强制刷新后重试一次
        try:
            new_token = await manager.refresh()
            result = await _post_send(client, new_token, openid, template_id, url, data)
            errcode = int(result.get("errcode", 0))
            if errcode == 0:
                return SendResult(openid=openid, ok=True)
        except (httpx.HTTPError, ValueError, WechatApiError) as exc:
            return SendResult(openid=openid, ok=False, errmsg=f"token 刷新后重试失败：{exc}")

    if errcode in _UNREACHABLE_ERRCODES:
        return SendResult(
            openid=openid, ok=False, errcode=errcode,
            errmsg=str(result.get("errmsg", "用户未关注公众号")),
        )

    return SendResult(
        openid=openid, ok=False, errcode=errcode,
        errmsg=str(result.get("errmsg", "微信返回错误")),
    )
